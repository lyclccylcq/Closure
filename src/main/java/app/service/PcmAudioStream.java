package app.service;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Decode WAV containers and feed their PCM into a single continuous, buffered output line. */
public final class PcmAudioStream implements VoiceAudioStream {
    @FunctionalInterface
    interface LineFactory {
        SourceDataLine create(AudioFormat format) throws LineUnavailableException;
    }

    public record Snapshot(int lineOpens,int startEvents,int unexpectedStops,long framesWritten,
                           float sampleRate,long firstPlaybackNanos,boolean drained) { }

    private final LineFactory lines;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(command->{
        Thread thread=new Thread(command,"closure-pcm-playback");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<CompletableFuture<Void>> pending=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final AtomicInteger lineOpens=new AtomicInteger();
    private final AtomicInteger starts=new AtomicInteger();
    private final AtomicInteger unexpectedStops=new AtomicInteger();
    private final AtomicLong firstPlayback=new AtomicLong();
    private CompletableFuture<Void> tail=CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> finished;
    private volatile SourceDataLine line;
    private volatile AudioFormat format;
    private volatile long framesWritten;
    private volatile boolean draining;
    private volatile boolean drained;
    private boolean started;
    private int prebufferBytes;

    public PcmAudioStream(){
        this(AudioSystem::getSourceDataLine);
    }

    PcmAudioStream(LineFactory lines){
        this.lines=lines;
    }

    @Override
    public synchronized CompletableFuture<Void> append(Path wav){
        if(finished!=null) return CompletableFuture.failedFuture(new IllegalStateException("Speech stream already ended"));
        return enqueue(()->write(wav));
    }

    @Override
    public synchronized CompletableFuture<Void> finish(){
        if(finished==null){
            finished=enqueue(()->{
                SourceDataLine current=line;
                if(current!=null){
                    draining=true;
                    if(!started){current.start();started=true;}
                    current.drain();
                    checkCancelled();
                    drained=true;
                }
                closeLine(false);
            });
            finished.whenComplete((unused,error)->writer.shutdown());
        }
        return finished;
    }

    @Override
    public void cancel(){
        if(!cancelled.compareAndSet(false,true)) return;
        for(var future:List.copyOf(pending)) future.cancel(false);
        closeLine(true);
        writer.shutdownNow();
    }

    public Snapshot snapshot(){
        AudioFormat current=format;
        return new Snapshot(lineOpens.get(),starts.get(),unexpectedStops.get(),framesWritten,
                current==null ? 0 : current.getSampleRate(),firstPlayback.get(),drained);
    }

    private CompletableFuture<Void> enqueue(IoAction action){
        if(cancelled.get()) return CompletableFuture.failedFuture(new CancellationException("Speech stopped"));
        tail=tail.thenRunAsync(()->{
            checkCancelled();
            try {
                action.run();
            } catch(CancellationException error){
                throw error;
            } catch(Exception error){
                throw new CompletionException(error);
            }
        },writer);
        CompletableFuture<Void> next=tail;
        pending.add(next);
        next.whenComplete((unused,error)->{
            pending.remove(next);
            if(error!=null && !cancelled.get()){closeLine(true);writer.shutdown();}
        });
        return next;
    }

    private void write(Path wav) throws Exception {
        try(AudioInputStream input=AudioSystem.getAudioInputStream(wav.toFile())){
            AudioFormat incoming=input.getFormat();
            if(!AudioFormat.Encoding.PCM_SIGNED.equals(incoming.getEncoding())
                    || incoming.getSampleSizeInBits()!=16 || incoming.isBigEndian()
                    || incoming.getFrameSize()<=0 || incoming.getSampleRate()<=0){
                throw new IOException("Expected little-endian PCM16 speech audio");
            }
            if(format==null) openLine(incoming);
            else if(!format.matches(incoming)) throw new IOException("Speech fragment format changed mid-stream");
            SourceDataLine current=line;
            checkCancelled();
            if(current==null) throw new CancellationException("Audio output closed");
            int frameSize=format.getFrameSize();
            int block=Math.max(frameSize,Math.min(frameSize*1024,current.getBufferSize()/2/frameSize*frameSize));
            byte[] bytes=new byte[block];
            int count;
            while((count=input.read(bytes))!=-1){
                checkCancelled();
                int offset=0;
                while(offset<count){
                    checkCancelled();
                    int copied=current.write(bytes,offset,count-offset);
                    if(copied<=0){checkCancelled();throw new IOException("Audio output stopped accepting samples");}
                    offset+=copied;
                    framesWritten+=copied/frameSize;
                    if(!started && framesWritten*frameSize>=prebufferBytes){
                        current.start();
                        started=true;
                    }
                }
            }
        }
    }

    private void openLine(AudioFormat incoming) throws LineUnavailableException {
        checkCancelled();
        SourceDataLine created=lines.create(incoming);
        try {
            int bufferBytes=(int)incoming.getSampleRate()*incoming.getFrameSize();
            created.addLineListener(event->{
                if(event.getType()==LineEvent.Type.START){
                    starts.incrementAndGet();
                    firstPlayback.compareAndSet(0,System.nanoTime());
                } else if(event.getType()==LineEvent.Type.STOP && !draining && !cancelled.get()){
                    unexpectedStops.incrementAndGet();
                }
            });
            created.open(incoming,bufferBytes);
            synchronized(this){
                checkCancelled();
                format=incoming;
                prebufferBytes=Math.max(incoming.getFrameSize(),Math.min(
                        (int)(incoming.getSampleRate()*incoming.getFrameSize()*0.24),created.getBufferSize()/2));
                line=created;
                lineOpens.incrementAndGet();
            }
        } catch(RuntimeException | LineUnavailableException error){
            created.close();
            throw error;
        }
    }

    private void closeLine(boolean discard){
        SourceDataLine current;
        synchronized(this){current=line;line=null;}
        if(current==null) return;
        try {
            current.stop();
            if(discard) current.flush();
        } finally {
            current.close();
        }
    }

    private void checkCancelled(){
        if(cancelled.get()) throw new CancellationException("Speech stopped");
    }

    @FunctionalInterface
    private interface IoAction { void run() throws Exception; }
}
