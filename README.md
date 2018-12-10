# ArpRec
ARCore Session Recorder - records poses and the raw camera feed of an ARCore session.
Based on the ARCore's SDK "HelloAR-java" sample and this SO answer https://stackoverflow.com/a/48066599/1641923, although the application in this repo does only record the raw camera feed without virtual objects.

In order to convert the resulting video into frames corresponding to the poses in the poses file one has to do:
```
ffmpeg -i video-<id>.mp4 -q:v 1 -vsync 0 frames/%d.png
```
where `-q:v 1` says that the image quality should be the highest possible. The parameter `-vsync 0` disables vsync, otherwise some duplicated frames would be extracted.

One can also downscale the images in the same command like: 
```
ffmpeg -i video-<id>.mp4 -q:v 1 -s 740x360 -vsync 0 frames/%d.png
```
