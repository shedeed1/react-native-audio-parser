 #import <AVFoundation/AVFoundation.h>
 #import <React/RCTEventEmitter.h>
 #import <React/RCTLog.h>
 #import "LibFFT.h"

 #define kNumberBuffers 3

 typedef struct {
     __unsafe_unretained id      mSelf;
     AudioStreamBasicDescription mDataFormat;
     AudioQueueRef               mQueue;
     AudioQueueBufferRef         mBuffers[kNumberBuffers];
     UInt32                      bufferByteSize;
     SInt64                      mCurrentPacket;
     bool                        mIsRunning;
 } AQRecordState;

 @interface AudioParser: RCTEventEmitter <RCTBridgeModule>
     @property (nonatomic, assign) AQRecordState recordState;
     @property (nonatomic, strong) LibFFT *fftProcessor;
     @property (nonatomic, assign) NSUInteger totalFrames;
     @property (nonatomic, assign) NSUInteger framesRead;
     @property (nonatomic, assign) BOOL shouldContinueReading;
     @property (strong, nonatomic) AVAudioPlayer *audioPlayer;
     @property (nonatomic) BOOL isAudioProcessingActive;
 @end
