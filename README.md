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
