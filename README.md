# Video streaming 
This is a proof-of-concept of video streaming from MongoDB utilizing [HLS](https://developer.apple.com/streaming/) (HTTP Live Streaming) technology designed by Apple.

The videos are stored in a database in [GridFS](https://www.mongodb.com/docs/manual/core/gridfs/), which allows storing binary files and also bypasses the 16 MB maximum document size. 

## HTTP Live Streaming
HTTP Live Streaming is a way of streaming videos by splitting them into approximately 1 MB chunks.

When a user starts watching the video, a file containing information about its length, resolution and other properties, called a manifest, is downloaded. A native HTML `video` element will then load individual video chunks while the video is being played by the browser - this way we do not have to send the whole video file, which could be expensive, could extend page load time and might be redundant, since the user might not watch the whole video. 

Streaming the video chunks guarantees the browser is going to get the minimum data necessary and won't overload the user's device in case of long and high-quality videos. 

Every video is therefore represented by a `manifest.m3u8` file containing a list of chunks saved as `indexX.ts`. These are binary data, that can be easily stored on a CDN. 

## Caching
MongoDB GridFS data queries can quickly become very resource heavy and slow - to optimize the performance, individual video files (especially chunk files) are cached upon a user request. 

[Redis](https://redis.io/) is a RAM-based key-value database which supports storing binary files. Storing videos (or at least some parts of them) improves performance, since RAM is the fastest memory on a server. Additional caching can also reduce database load.

Obviously, storing every video in a cache would not make sense, since the user might not even watch the whole video - the video is cached on demand - this way, frequently watched videos are going to be easily accessible without a single database lookup. In case of a cache miss, however, there is a prolonged delay in the data stream.

To further improve performance, [IndexedDB](https://developer.mozilla.org/en-US/docs/Web/API/IndexedDB_API) can be used on the frontend to avoid database lookups for video chunks that have already been loaded.

## Video processing
Videos (in `mp4` format) uploaded using the `/videos/upload` endpoint are processed by [FFmpeg](https://www.ffmpeg.org/) via [JavaCV](https://github.com/bytedeco/javacv). At first, a `manifest.m3u8` file is generated, then the video is split into individual `.ts` chunks. 
The endpoint also generates a video thumbnail at the 2nd second of the video using FFmpeg. This thumbnail is available on a GET request to `/videos/<id>/thumbnail`.

## Frontend
Since HLS is an Apple-made technology, a standard `video`  HTML element will only support HLS in Safari. To extend the support for other browsers (like Google Chrome, Firefox, etc.) we can use library like [hls.js](https://github.com/video-dev/hls.js).

Under normal circumstances, the `src` attribute would include a link to a media file. In this case, however, a link to `manifest.m3u8` is required.

**Minimal `hls.js` example** ([source](https://github.com/video-dev/hls.js/#embedding-hlsjs))
```html
<script src="https://cdn.jsdelivr.net/npm/hls.js@1"></script>
<!-- Or if you want the latest version from the main branch -->
<!-- <script src="https://cdn.jsdelivr.net/npm/hls.js@canary"></script> -->
<video id="video"></video>
<script>
  var video = document.getElementById('video');
  var videoSrc = 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8';
  if (Hls.isSupported()) {
    var hls = new Hls();
    hls.loadSource(videoSrc);
    hls.attachMedia(video);
  }
  // HLS.js is not supported on platforms that do not have Media Source
  // Extensions (MSE) enabled.
  //
  // When the browser has built-in HLS support (check using `canPlayType`),
  // we can provide an HLS manifest (i.e. .m3u8 URL) directly to the video
  // element through the `src` property. This is using the built-in support
  // of the plain video element, without using HLS.js.
  else if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = videoSrc;
  }
</script>
```

## How to Run
System requirements: Java 25, Docker

1. Clone this repository
2. Start database and cache: `docker compose up -d`
3. Start backend: 
   - Linux/macOS: `./gradlew run` 
   - Windows: `.\gradlew.bat run`

**Dependencies**: this project uses FFmpeg via JavaCV, however, FFprobe which is used to obtain video file data (such as video duration), is part of FFmpeg, but is not included in the JavaCV library and has to be downloaded separately from [GitHub](https://github.com/GyanD/codexffmpeg/)
