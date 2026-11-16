package app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.sampled.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class PcmAudioStreamTest {
    @TempDir Path temporary;

    @Test
    void fragmentsUseOneDeviceAndKeepTheirExactPcmWithoutDrainingBetweenThem() throws Exception {
        FakeLine device=new FakeLine();
        var stream=new PcmAudioStream(format->device.line);
        byte[] first=new byte[30_720];Arrays.fill(first,(byte)5);
        byte[] second=new byte[30_720];Arrays.fill(second,(byte)9);
        try {
            stream.append(wav("first.wav",first)).get(2,TimeUnit.SECONDS);
            stream.append(wav("second.wav",second)).get(2,TimeUnit.SECONDS);
            assertEquals(1,device.opens);
            assertEquals(1,device.starts);
            assertEquals(0,device.drains);
            byte[] expected=new byte[first.length+second.length];
            System.arraycopy(first,0,expected,0,first.length);
            System.arraycopy(second,0,expected,first.length,second.length);
            assertArrayEquals(expected,device.bytes.toByteArray());
            stream.finish().get(2,TimeUnit.SECONDS);
            assertEquals(1,device.drains);
            assertTrue(stream.snapshot().drained());
            assertFalse(device.open.get());
        } finally {stream.cancel();}
    }

    @Test
    void finishWaitsForTheDeviceWhileConsumedTemporaryFilesCanBeDeleted() throws Exception {
        FakeLine device=new FakeLine();
        device.drainRelease=new CountDownLatch(1);
        var stream=new PcmAudioStream(format->device.line);
        Path file=wav("consumed.wav",new byte[30_720]);
        try {
            stream.append(file).get(2,TimeUnit.SECONDS);
            Files.delete(file);
            var finished=stream.finish();
            assertTrue(device.draining.await(2,TimeUnit.SECONDS));
            assertFalse(finished.isDone());
            device.drainRelease.countDown();
            finished.get(2,TimeUnit.SECONDS);
        } finally {stream.cancel();}
    }

    @Test
    void cancellationUnblocksTheWriterAndCompletesQueuedFragments() throws Exception {
        FakeLine device=new FakeLine();
        device.writeRelease=new CountDownLatch(1);
        var stream=new PcmAudioStream(format->device.line);
        var first=stream.append(wav("blocked.wav",new byte[30_720]));
        var second=stream.append(wav("queued.wav",new byte[30_720]));
        try {
            assertTrue(device.writing.await(2,TimeUnit.SECONDS));
            stream.cancel();
            assertThrows(CancellationException.class,()->first.get(2,TimeUnit.SECONDS));
            assertTrue(second.isDone());
            assertTrue(second.isCompletedExceptionally());
            assertFalse(device.open.get());
            assertEquals(1,device.flushes);
        } finally {stream.cancel();}
    }

    private Path wav(String name,byte[] pcm) throws Exception {
        var format=new AudioFormat(24_000,16,1,true,false);
        Path path=temporary.resolve(name);
        try(var input=new AudioInputStream(new ByteArrayInputStream(pcm),format,pcm.length/2)){
            AudioSystem.write(input,AudioFileFormat.Type.WAVE,path.toFile());
        }
        return path;
    }

    private static final class FakeLine {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final AtomicBoolean open=new AtomicBoolean();
        final CountDownLatch writing=new CountDownLatch(1);
        final CountDownLatch draining=new CountDownLatch(1);
        final List<LineListener> listeners=new CopyOnWriteArrayList<>();
        volatile CountDownLatch writeRelease;
        volatile CountDownLatch drainRelease;
        volatile int opens,starts,drains,flushes;
        AudioFormat format;
        final SourceDataLine line=(SourceDataLine)Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),
                new Class<?>[]{SourceDataLine.class},(proxy,method,args)->{
                    switch(method.getName()){
                        case "open" -> {format=(AudioFormat)args[0];opens++;open.set(true);return null;}
                        case "getFormat" -> {return format;}
                        case "getBufferSize" -> {return 48_000;}
                        case "isOpen" -> {return open.get();}
                        case "addLineListener" -> {listeners.add((LineListener)args[0]);return null;}
                        case "write" -> {
                            writing.countDown();
                            if(writeRelease!=null) writeRelease.await(2,TimeUnit.SECONDS);
                            if(!open.get()) return 0;
                            bytes.write((byte[])args[0],(int)args[1],(int)args[2]);
                            return (int)args[2];
                        }
                        case "start" -> {
                            starts++;
                            for(var listener:listeners) listener.update(new LineEvent((SourceDataLine)proxy,LineEvent.Type.START,0));
                            return null;
                        }
                        case "drain" -> {
                            drains++;draining.countDown();
                            if(drainRelease!=null) drainRelease.await(2,TimeUnit.SECONDS);
                            return null;
                        }
                        case "flush" -> {flushes++;return null;}
                        case "close" -> {open.set(false);if(writeRelease!=null)writeRelease.countDown();return null;}
                        case "toString" -> {return "Fake PCM device";}
                    }
                    if(method.getReturnType()==boolean.class) return false;
                    if(method.getReturnType()==int.class) return 0;
                    if(method.getReturnType()==long.class) return 0L;
                    if(method.getReturnType()==float.class) return 0f;
                    return null;
                });
    }
}
