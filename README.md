# Video streaming 
This is a proof-of-concept of video streaming from MongoDB utilizing [HLS](https://developer.apple.com/streaming/) (HTTP Live Streaming) technology designed by Apple.

The videos are stored in a database in [GridFS](https://www.mongodb.com/docs/manual/core/gridfs/), which allows storing binary files and also bypasses the 16 MB maximum document size. 
