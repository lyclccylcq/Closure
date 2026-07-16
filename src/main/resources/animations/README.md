The desktop pet animation runs from PNG frames under action directories:

closure/open/frame_001.png
closure/open/frame_002.png
...

Register available actions in:

src/main/java/app/ui/pet/PetAction.java

For example, future actions can use their own directories:

closure/idle/frame_001.png
closure/idle/frame_002.png
closure/touch/frame_001.png
closure/touch/frame_002.png
...

The original source video can stay under:

closure/source/closure.webm

Regenerate frames with:

/opt/homebrew/bin/ffmpeg -y -c:v libvpx -i closure/source/closure.webm -vf fps=12,scale=220:220:flags=lanczos,format=rgba closure/open/frame_%03d.png
