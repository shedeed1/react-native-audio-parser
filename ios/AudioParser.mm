#import "AudioParser.h"

@implementation AudioParser

RCT_EXPORT_MODULE();

RCT_EXPORT_METHOD(init:(NSDictionary *) options) {
    RCTLogInfo(@"[AudioParser] init");
    _recordState.mDataFormat.mSampleRate        = options[@"sampleRate"] == nil ? 44100 : [options[@"sampleRate"] doubleValue];
    _recordState.mDataFormat.mBitsPerChannel    = options[@"bitsPerSample"] == nil ? 16 : [options[@"bitsPerSample"] unsignedIntValue];
    _recordState.mDataFormat.mChannelsPerFrame  = options[@"channels"] == nil ? 1 : [options[@"channels"] unsignedIntValue];
    _recordState.mDataFormat.mBytesPerPacket    = (_recordState.mDataFormat.mBitsPerChannel / 8) * _recordState.mDataFormat.mChannelsPerFrame;
    _recordState.mDataFormat.mBytesPerFrame     = _recordState.mDataFormat.mBytesPerPacket;
    _recordState.mDataFormat.mFramesPerPacket   = 1;
    _recordState.mDataFormat.mReserved          = 0;
    _recordState.mDataFormat.mFormatID          = kAudioFormatLinearPCM;
  _recordState.mDataFormat.mFormatFlags = (_recordState.mDataFormat.mBitsPerChannel == 8) ?
      kLinearPCMFormatFlagIsPacked :
      (kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked);
  NSUInteger bucketCount = options[@"bucketCount"] == nil ? 512 : [options[@"bucketCount"] unsignedIntValue];
      _recordState.bufferByteSize = (UInt32)bucketCount * 2;
  self.fftProcessor = [[LibFFT alloc] initWithBufferSize:bucketCount * 2];
    _recordState.mSelf = self;
}

RCT_EXPORT_METHOD(start) {
    RCTLogInfo(@"[AudioParser] start");
    AVAudioSession *audioSession = [AVAudioSession sharedInstance];
    NSError *error = nil;
    BOOL success;

    if (@available(iOS 10.0, *)) {
        success = [audioSession setCategory: AVAudioSessionCategoryPlayAndRecord
                                       mode: AVAudioSessionModeDefault
                                    options: AVAudioSessionCategoryOptionDefaultToSpeaker |
                                             AVAudioSessionCategoryOptionAllowBluetooth |
                                             AVAudioSessionCategoryOptionAllowAirPlay
                                      error: &error];
    } else {
        success = [audioSession setCategory: AVAudioSessionCategoryPlayAndRecord withOptions: AVAudioSessionCategoryOptionDefaultToSpeaker error: &error];
        success = [audioSession setMode: AVAudioSessionModeDefault error: &error] && success;
    }
    if (!success || error != nil) {
        RCTLog(@"[AudioParser] Problem setting up AVAudioSession category and mode. Error: %@", error);
        return;
    }

    _recordState.mIsRunning = true;

    OSStatus status = AudioQueueNewInput(&_recordState.mDataFormat, HandleInputBuffer, &_recordState, NULL, NULL, 0, &_recordState.mQueue);
    if (status != 0) {
        RCTLog(@"[AudioParser] Record Failed. Cannot initialize AudioQueueNewInput. status: %i", (int) status);
        return;
    }

    for (int i = 0; i < kNumberBuffers; i++) {
        AudioQueueAllocateBuffer(_recordState.mQueue, _recordState.bufferByteSize, &_recordState.mBuffers[i]);
        AudioQueueEnqueueBuffer(_recordState.mQueue, _recordState.mBuffers[i], 0, NULL);
    }
    AudioQueueStart(_recordState.mQueue, NULL);
}

RCT_EXPORT_METHOD(stop) {
    RCTLogInfo(@"[AudioParser] stop");
    if (_recordState.mIsRunning) {
        _recordState.mIsRunning = false;
        AudioQueueStop(_recordState.mQueue, true);
        for (int i = 0; i < kNumberBuffers; i++) {
            AudioQueueFreeBuffer(_recordState.mQueue, _recordState.mBuffers[i]);
        }
        AudioQueueDispose(_recordState.mQueue, true);
    }
}

void HandleInputBuffer(void *inUserData, AudioQueueRef inAQ, AudioQueueBufferRef inBuffer, const AudioTimeStamp *inStartTime, UInt32 inNumPackets, const AudioStreamPacketDescription *inPacketDesc) {
    AQRecordState *pRecordState = (AQRecordState *)inUserData;
    if (!pRecordState->mIsRunning) return;

    // Handling based on the bit depth
    if (pRecordState->mDataFormat.mBitsPerChannel == 16) {
        short *samples = (short *)inBuffer->mAudioData;
        int numSamples = inBuffer->mAudioDataByteSize / sizeof(short);
        process16BitSamples(samples, numSamples, pRecordState, inBuffer);
    } else if (pRecordState->mDataFormat.mBitsPerChannel == 8) {
        uint8_t *samples = (uint8_t *)inBuffer->mAudioData;
        int numSamples = inBuffer->mAudioDataByteSize / sizeof(uint8_t);
        process8BitSamples(samples, numSamples, pRecordState, inBuffer);
    }
}

void processSamples(void *audioData, int numSamples, int bitDepth, BOOL isStereo, AQRecordState *pRecordState, AudioQueueBufferRef inBuffer) {
    NSMutableArray<NSNumber *> *allSamples = [NSMutableArray arrayWithCapacity:numSamples];
    NSMutableArray<NSNumber *> *leftChannelSamples = isStereo ? [NSMutableArray arrayWithCapacity:numSamples/2] : nil;
    NSMutableArray<NSNumber *> *rightChannelSamples = isStereo ? [NSMutableArray arrayWithCapacity:numSamples/2] : nil;
    double totalVolume = 0, leftVolume = 0, rightVolume = 0;

    if (bitDepth == 16) {
        short *samples = (short *)audioData;
        for (int i = 0; i < numSamples; i++) {
            [allSamples addObject:@(samples[i])];
            totalVolume += abs(samples[i]);
            if (isStereo) {
                if (i % 2 == 0) {  // Left channel
                    [leftChannelSamples addObject:@(samples[i])];
                    leftVolume += abs(samples[i]);
                } else {  // Right channel
                    [rightChannelSamples addObject:@(samples[i])];
                    rightVolume += abs(samples[i]);
                }
            }
        }
    } else { // 8-bit processing
        uint8_t *samples = (uint8_t *)audioData;
        for (int i = 0; i < numSamples; i++) {
            int normalizedSample = (int)samples[i] - 128;  // Normalize around zero
            [allSamples addObject:@(normalizedSample)];
            totalVolume += abs(normalizedSample);
            if (isStereo) {
                if (i % 2 == 0) {  // Left channel
                    [leftChannelSamples addObject:@(normalizedSample)];
                    leftVolume += abs(normalizedSample);
                } else {  // Right channel
                    [rightChannelSamples addObject:@(normalizedSample)];
                    rightVolume += abs(normalizedSample);
                }
            }
        }
    }

    totalVolume /= numSamples;
    AudioParser *parser = (AudioParser *)pRecordState->mSelf;
    NSArray<NSNumber *> *fftResults = [parser.fftProcessor transform:allSamples];

    NSMutableDictionary *eventBody = [@{@"volume": @(totalVolume), @"buckets": fftResults} mutableCopy];

    if (isStereo) {
        leftVolume /= (numSamples / 2);
        rightVolume /= (numSamples / 2);
        NSArray<NSNumber *> *leftFFTResults = [parser.fftProcessor transform:leftChannelSamples];
        NSArray<NSNumber *> *rightFFTResults = [parser.fftProcessor transform:rightChannelSamples];
        NSDictionary *leftChannelData = @{@"volume": @(leftVolume), @"buckets": leftFFTResults};
        NSDictionary *rightChannelData = @{@"volume": @(rightVolume), @"buckets": rightFFTResults};
        eventBody[@"channel"] = @{@"left": leftChannelData, @"right": rightChannelData};
    }

    [pRecordState->mSelf sendEventWithName:@"audioData" body:eventBody];
    AudioQueueEnqueueBuffer(pRecordState->mQueue, inBuffer, 0, NULL);
}

void process16BitSamples(short *samples, int numSamples, AQRecordState *pRecordState, AudioQueueBufferRef inBuffer) {
    processSamples(samples, numSamples, 16, pRecordState->mDataFormat.mChannelsPerFrame == 2, pRecordState, inBuffer);
}

void process8BitSamples(uint8_t *samples, int numSamples, AQRecordState *pRecordState, AudioQueueBufferRef inBuffer) {
    processSamples(samples, numSamples, 8, pRecordState->mDataFormat.mChannelsPerFrame == 2, pRecordState, inBuffer);
}

- (NSArray<NSString *> *)supportedEvents {
    return @[@"audioData"];
}

- (void)dealloc {
    RCTLogInfo(@"[AudioParser] dealloc");
    AudioQueueDispose(_recordState.mQueue, true);
}

@end
