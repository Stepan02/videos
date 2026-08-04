# Video streaming 
This is a proof-of-concept of video streaming from MongoDB utilizing [HLS](https://developer.apple.com/streaming/) (HTTP Live Streaming) technology designed by Apple.

The videos are stored in a database in [GridFS](https://www.mongodb.com/docs/manual/core/gridfs/), which allows storing binary files and also bypasses the 16 MB maximum document size. 

## HTTP Live Streaming
HTTP Live Streaming is a way of streaming videos by splitting them into approximately 1 MB chunks.

When a user starts watching the video, a file containing information about its length, resolution and other properties, called a manifest, is downloaded. A native HTML `video` element will then load individual video chunks while the video is being played by the browser - this way we do not have to send the whole video file, which could be expensive, could extend page load time and might be redundant, since the user might not watch the whole video. 

Streaming the video chunks guarantees the browser is going to get the minimum data necessary and won't overload the user's device in case of long and high-quality videos. 

Every video is therefore represented by a `manifest.m3u8` file containing a list of chunks saved as `indexX.ts`. These are binary data, that can be easily stored on a CDN. 
