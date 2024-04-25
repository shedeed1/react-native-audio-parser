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

RCT_EXPORT_METHOD(startFromFile:(NSString *)fileURI) {
    self.currentAudioSource = @"FileData";
     if (self.isAudioProcessingActive) {
          RCTLogError(@"[AudioParser] Audio processing is already active.");
          return;
      }
     self.isAudioProcessingActive = YES;
    _shouldContinueReading = YES;
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSURL *fileURL = [NSURL URLWithString:fileURI];
        NSError *error = nil;
        AVAudioFile *audioFile = [[AVAudioFile alloc] initForReading:fileURL error:&error];

        if (!audioFile || error) {
            self.isAudioProcessingActive = NO;
            RCTLogError(@"[AudioParser] Could not open file: %@", error);
            return;
        }

        NSError *playerError = nil;
        self.audioPlayer = [[AVAudioPlayer alloc] initWithContentsOfURL:fileURL error:&playerError];
        if (!self.audioPlayer || playerError) {
            self.isAudioProcessingActive = NO;
            RCTLogError(@"[AudioParser] Could not create audio player: %@", playerError);
            return;
        }

        self.totalFrames = (NSUInteger)audioFile.length;
        self.framesRead = 0;

        [self.audioPlayer play];
        [self setupFileReadingSession:audioFile];
    });
}

- (void)setupFileReadingSession:(AVAudioFile *)audioFile {
    AVAudioFormat *processingFormat = audioFile.processingFormat;
    AVAudioFrameCount bufferSize = _recordState.bufferByteSize;

    while (_shouldContinueReading && self.audioPlayer.isPlaying) {
        AVAudioPCMBuffer *pcmBuffer = [[AVAudioPCMBuffer alloc] initWithPCMFormat:processingFormat frameCapacity:bufferSize];
        NSError *error = nil;

        BOOL success = [audioFile readIntoBuffer:pcmBuffer error:&error];
        if (!success || error) {
            break;
        }

        if (pcmBuffer.frameLength == 0) {
            break;  // EOF
        }

        self.framesRead += pcmBuffer.frameLength;
        [self processAudioBuffer:pcmBuffer format:processingFormat];

        // Synchronize the data emission to the playback time
        double processedTime = (double)self.framesRead / processingFormat.sampleRate;
        double playbackTime = self.audioPlayer.currentTime;
        if (processedTime > playbackTime) {
            [NSThread sleepForTimeInterval:processedTime - playbackTime];
        }
    }
}

- (void)processAudioBuffer:(AVAudioPCMBuffer *)buffer format:(AVAudioFormat *)format {
    // Check the audio format
    UInt32 bitDepth = format.streamDescription->mBitsPerChannel;
    BOOL isStereo = format.channelCount == 2;

    // Prepare arrays for processing
    NSMutableArray<NSNumber *> *allSamples = [NSMutableArray arrayWithCapacity:buffer.frameLength];
    NSMutableArray<NSNumber *> *leftChannelSamples = isStereo ? [NSMutableArray arrayWithCapacity:buffer.frameLength/2] : nil;
    NSMutableArray<NSNumber *> *rightChannelSamples = isStereo ? [NSMutableArray arrayWithCapacity:buffer.frameLength/2] : nil;
    double peakVolume = 0, leftPeakVolume = 0, rightPeakVolume = 0;
    double maxPossibleVolume = (bitDepth == 16 ? 32768.0 : (bitDepth == 8 ? 128.0 : 1.0));  // Adjust max volume for 32-bit

    // Extract samples from buffer
    if (bitDepth == 16) {
        short *samples = (short *)buffer.int16ChannelData[0];  // Assuming non-interleaved data
        for (int i = 0; i < buffer.frameLength; i++) {
            double absSample = abs(samples[i]);
            peakVolume = fmax(peakVolume, absSample);
            [allSamples addObject:@(samples[i])];
            if (isStereo) {
                double leftSample = abs(samples[2*i]);
                double rightSample = abs(samples[2*i+1]);
                leftPeakVolume = fmax(leftPeakVolume, leftSample);
                rightPeakVolume = fmax(rightPeakVolume, rightSample);
                [leftChannelSamples addObject:@(samples[2*i])];
                [rightChannelSamples addObject:@(samples[2*i+1])];
            }
        }
    } else if (bitDepth == 8) {
        uint8_t *samples = (uint8_t *)buffer.int16ChannelData[0];
        for (int i = 0; i < buffer.frameLength; i++) {
            int normalizedSample = (int)samples[i] - 128;
            double absSample = abs(normalizedSample);
            peakVolume = fmax(peakVolume, absSample);
            [allSamples addObject:@(normalizedSample)];
            if (isStereo) {
                double leftSample = abs(samples[2*i] - 128);
                double rightSample = abs(samples[2*i+1] - 128);
                leftPeakVolume = fmax(leftPeakVolume, leftSample);
                rightPeakVolume = fmax(rightPeakVolume, rightSample);
                [leftChannelSamples addObject:@(samples[2*i] - 128)];
                [rightChannelSamples addObject:@(samples[2*i+1] - 128)];
            }
        }
    } else if (bitDepth == 32) {
        float *samples = (float *)buffer.floatChannelData[0];
        for (int i = 0; i < buffer.frameLength; i++) {
            double absSample = fabs(samples[i]);
            peakVolume = fmax(peakVolume, absSample);
            [allSamples addObject:@(samples[i])];
            if (isStereo) {
                double leftSample = fabs(samples[2*i]);
                double rightSample = fabs(samples[2*i+1]);
                leftPeakVolume = fmax(leftPeakVolume, leftSample);
                rightPeakVolume = fmax(rightPeakVolume, rightSample);
                [leftChannelSamples addObject:@(samples[2*i])];
                [rightChannelSamples addObject:@(samples[2*i+1])];
            }
        }
    }

    double readingPercentage = ((double)self.framesRead / (double)self.totalFrames) * 100.0;

    // Normalize peak volumes
    double normalizedPeakVolume = [self normalizeVolume:peakVolume withMaxPossibleVolume:maxPossibleVolume minTargetVolume:0.0 maxTargetVolume:10.0];
    NSArray<NSNumber *> *fftResults = [self.fftProcessor transform:allSamples];
    NSMutableDictionary *eventBody;
    if ([self.currentAudioSource  isEqual: @"FileData"])
    {
        eventBody = [@{
                @"volume": @(normalizedPeakVolume),
                @"buckets": fftResults,
                @"percentageRead": @(readingPercentage)
            } mutableCopy];
    }
    else {
        eventBody = [@{
                @"volume": @(normalizedPeakVolume),
                @"buckets": fftResults,
                @"percentageRead": @((self.stkAudioPlayer.progress / self.stkAudioPlayer.duration) * 100)
            } mutableCopy];
    }
   

    if (isStereo) {
        double normalizedLeftPeakVolume = [self normalizeVolume:leftPeakVolume withMaxPossibleVolume:maxPossibleVolume minTargetVolume:0.0 maxTargetVolume:10.0];
        double normalizedRightPeakVolume = [self normalizeVolume:rightPeakVolume withMaxPossibleVolume:maxPossibleVolume minTargetVolume:0.0 maxTargetVolume:10.0];
        NSArray<NSNumber *> *leftFFTResults = [self.fftProcessor transform:leftChannelSamples];
        NSArray<NSNumber *> *rightFFTResults = [self.fftProcessor transform:rightChannelSamples];
        NSDictionary *leftChannelData = @{@"volume": @(normalizedLeftPeakVolume), @"buckets": leftFFTResults};
        NSDictionary *rightChannelData = @{@"volume": @(normalizedRightPeakVolume), @"buckets": rightFFTResults};
        eventBody[@"channel"] = @{@"left": leftChannelData, @"right": rightChannelData};
    }

    // Emit the event
    [self sendEventWithName:self.currentAudioSource body:eventBody];
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

RCT_EXPORT_METHOD(stopReadingFile) {
    dispatch_async(dispatch_get_main_queue(), ^{
        [self.audioPlayer stop];
        self.audioPlayer = nil;
        RCTLogInfo(@"Stopping file read");
        self.shouldContinueReading = NO;
        self.isAudioProcessingActive = NO;
    });
}
RCT_EXPORT_METHOD(startFromURL:(NSString *)urlString) {
    self.currentAudioSource = @"URLData";
    self.stkAudioPlayer = [[STKAudioPlayer alloc] initWithOptions:(STKAudioPlayerOptions){ .flushQueueOnSeek = YES }];
    [self.stkAudioPlayer play:urlString];


    // Append a frame filter to process audio frames
    [self.stkAudioPlayer appendFrameFilterWithName:@"MyCustomFilter" block:^(UInt32 channelsPerFrame, UInt32 bytesPerFrame, UInt32 frameCount, void* frames) {
        int16_t *samples = (int16_t *)frames;
        NSUInteger numSamples = frameCount * channelsPerFrame;

        // Convert the raw frames to an AVAudioPCMBuffer for processing
        AVAudioFormat *format = [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatInt16 sampleRate:self->_recordState.mDataFormat.mSampleRate channels:channelsPerFrame interleaved:YES];

        AVAudioPCMBuffer *pcmBuffer = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:frameCount];
        memcpy(pcmBuffer.int16ChannelData[0], samples, bytesPerFrame * frameCount);

        pcmBuffer.frameLength = frameCount;

        dispatch_async(dispatch_get_main_queue(), ^{
            [self processAudioBuffer:pcmBuffer format:format];
        });
    }];
}
RCT_EXPORT_METHOD(stopReadingURL) {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.stkAudioPlayer) {
            [self.stkAudioPlayer stop];
            [self.stkAudioPlayer dispose];
            self.stkAudioPlayer = nil;
            RCTLogInfo(@"[AudioParser] Stopped reading from URL");
        }
        self.isAudioProcessingActive = NO;
    });
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
    NSMutableArray<NSNumber *> *rawDataArray = [NSMutableArray arrayWithCapacity:numSamples];
    NSMutableArray<NSNumber *> *leftChannelSamples = isStereo ? [NSMutableArray arrayWithCapacity:numSamples/2] : nil;
    NSMutableArray<NSNumber *> *rightChannelSamples = isStereo ? [NSMutableArray arrayWithCapacity:numSamples/2] : nil;
    double peakVolume = 0, leftPeakVolume = 0, rightPeakVolume = 0;
    double maxPossibleVolume = bitDepth == 16 ? 32768.0 : (bitDepth == 8 ? 128.0 : 1.0);  // Adjust max volume for bit depth

    for (int i = 0; i < numSamples; i++) {
        double sampleValue = 0;
        if (bitDepth == 16) {
            short *samples = (short *)audioData;
            sampleValue = samples[i];
            [rawDataArray addObject:@((int)sampleValue)];
        } else if (bitDepth == 8) {
            uint8_t *samples = (uint8_t *)audioData;
            sampleValue = (double)samples[i] - 128;  // Normalize around zero for 8-bit audio
            int sampleValue = (int)samples[i] & 0xFF;  // Convert byte to unsigned int
            [rawDataArray addObject:@(sampleValue)];
        } else if (bitDepth == 32) {
            float *samples = (float *)audioData;
            sampleValue = samples[i];
        }

        double absSample = fabs(sampleValue);
        [allSamples addObject:@(sampleValue)];
        peakVolume = fmax(peakVolume, absSample);  // Update peak volume

        if (isStereo) {
            if (i % 2 == 0) {  // Left channel
                [leftChannelSamples addObject:@(sampleValue)];
                leftPeakVolume = fmax(leftPeakVolume, absSample);
            } else {  // Right channel
                [rightChannelSamples addObject:@(sampleValue)];
                rightPeakVolume = fmax(rightPeakVolume, absSample);
            }
        }
    }

    AudioParser *parser = (AudioParser *)pRecordState->mSelf;
    double normalizedPeakVolume = [parser normalizeVolume:peakVolume withMaxPossibleVolume:maxPossibleVolume minTargetVolume:0.0 maxTargetVolume:10.0];
    NSArray<NSNumber *> *fftResults = [parser.fftProcessor transform:allSamples];

  NSMutableDictionary *eventBody = [@{
         @"volume": @(normalizedPeakVolume),
         @"buckets": fftResults,
         @"rawBuffer": rawDataArray
     } mutableCopy];
    if (isStereo) {
        double normalizedLeftPeakVolume = [parser normalizeVolume:leftPeakVolume withMaxPossibleVolume:maxPossibleVolume minTargetVolume:0.0 maxTargetVolume:10.0];
        double normalizedRightPeakVolume = [parser normalizeVolume:rightPeakVolume withMaxPossibleVolume:maxPossibleVolume minTargetVolume:0.0 maxTargetVolume:10.0];
        NSArray<NSNumber *> *leftFFTResults = [parser.fftProcessor transform:leftChannelSamples];
        NSArray<NSNumber *> *rightFFTResults = [parser.fftProcessor transform:rightChannelSamples];
        NSDictionary *leftChannelData = @{@"volume": @(normalizedLeftPeakVolume), @"buckets": leftFFTResults};
        NSDictionary *rightChannelData = @{@"volume": @(normalizedRightPeakVolume), @"buckets": rightFFTResults};
        eventBody[@"channel"] = @{@"left": leftChannelData, @"right": rightChannelData};
    }

    [pRecordState->mSelf sendEventWithName:@"RecordingData" body:eventBody];
    AudioQueueEnqueueBuffer(pRecordState->mQueue, inBuffer, 0, NULL);
}

void process16BitSamples(short *samples, int numSamples, AQRecordState *pRecordState, AudioQueueBufferRef inBuffer) {
    processSamples(samples, numSamples, 16, pRecordState->mDataFormat.mChannelsPerFrame == 2, pRecordState, inBuffer);
}

void process8BitSamples(uint8_t *samples, int numSamples, AQRecordState *pRecordState, AudioQueueBufferRef inBuffer) {
    processSamples(samples, numSamples, 8, pRecordState->mDataFormat.mChannelsPerFrame == 2, pRecordState, inBuffer);
}

- (double)normalizeVolume:(double)volume withMaxPossibleVolume:(double)maxPossibleVolume minTargetVolume:(double)minTargetVolume maxTargetVolume:(double)maxTargetVolume {
    return minTargetVolume + (volume / maxPossibleVolume) * (maxTargetVolume - minTargetVolume);
}

- (NSArray<NSString *> *)supportedEvents {
    return @[@"RecordingData", @"FileData", @"URLData"];
}

- (void)dealloc {
      if (self.audioPlayer.isPlaying) {
          [self.audioPlayer stop];
      }
      self.audioPlayer = nil;
     if (_recordState.mIsRunning) {
        AudioQueueStop(_recordState.mQueue, true);
        AudioQueueDispose(_recordState.mQueue, true);
        _recordState.mIsRunning = false;
     }
     self.isAudioProcessingActive = NO;
     RCTLogInfo(@"[AudioParser] dealloc");
}

@end
