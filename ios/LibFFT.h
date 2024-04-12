#import <Foundation/Foundation.h>
#import <Accelerate/Accelerate.h>

@interface LibFFT : NSObject

@property (nonatomic, readonly) NSInteger bufferSize;

- (instancetype)initWithBufferSize:(NSInteger)bufferSize;
- (NSArray<NSNumber *> *)transform:(NSArray<NSNumber *> *)inBuffer;

@end
