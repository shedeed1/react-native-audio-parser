#import "LibFFT.h"

@interface LibFFT ()

@property (nonatomic) NSInteger fftNLog;
@property (nonatomic) NSInteger fftN;
@property (nonatomic) double miny;
@property (nonatomic) NSMutableArray<NSNumber *> *real;
@property (nonatomic) NSMutableArray<NSNumber *> *imag;
@property (nonatomic) NSMutableArray<NSNumber *> *sinTable;
@property (nonatomic) NSMutableArray<NSNumber *> *cosTable;
@property (nonatomic) NSMutableArray<NSNumber *> *bitReverse;

@end

@implementation LibFFT
- (instancetype)initWithBufferSize:(NSInteger)bufferSize {
    self = [super init];
    if (self) {
        _fftNLog = round(log2(bufferSize));
        _fftN = 1 << _fftNLog;
        _miny = (_fftN << 2) * sqrt(2.0);

        _real = [NSMutableArray arrayWithCapacity:_fftN];
        _imag = [NSMutableArray arrayWithCapacity:_fftN];
        _sinTable = [NSMutableArray arrayWithCapacity:_fftN / 2];
        _cosTable = [NSMutableArray arrayWithCapacity:_fftN / 2];
        _bitReverse = [NSMutableArray arrayWithCapacity:_fftN];

        for (NSInteger i = 0; i < _fftN; i++) {
            [_real addObject:@(0.0)];
            [_imag addObject:@(0.0)];
        }

        // Populate _bitReverse array
        for (NSInteger i = 0; i < _fftN; i++) {
            NSInteger j = 0;
            for (NSInteger bit = 0; bit < _fftNLog; bit++) {
                if (i & (1 << bit)) {
                    j |= 1 << ((_fftNLog - 1) - bit);
                }
            }
            [_bitReverse addObject:@(j)];
        }

        for (NSInteger i = 0; i < _fftN / 2; i++) {
            double theta = i * 2 * M_PI / _fftN;
            [_cosTable addObject:@(cos(theta))];
            [_sinTable addObject:@(sin(theta))];
        }
    }
    return self;
}

- (NSArray<NSNumber *> *)transform:(NSArray<NSNumber *> *)inBuffer {
    NSMutableArray<NSNumber *> *outBuffer = [NSMutableArray arrayWithCapacity:_fftN / 2];

    NSInteger j0 = 1;
    NSInteger idx = _fftNLog - 1;
    double cosv, sinv, tmpr, tmpi;

    for (NSInteger i = 0; i < _fftN; i++) {
      NSInteger index = [_bitReverse[i] integerValue];
      _real[i] = @(i < inBuffer.count && index < inBuffer.count ? [inBuffer[index] floatValue] : 0.0);
        _imag[i] = @(0.0);
    }

    for (NSInteger i = _fftNLog; i != 0; i--) {
        NSInteger j = 0;
        while (j != j0) {
            cosv = [_cosTable[j * (1 << idx)] doubleValue];
            sinv = [_sinTable[j * (1 << idx)] doubleValue];
            NSInteger k = j;
            while (k < _fftN) {
                NSInteger ir = k + j0;
                tmpr = cosv * [_real[ir] doubleValue] - sinv * [_imag[ir] doubleValue];
                tmpi = cosv * [_imag[ir] doubleValue] + sinv * [_real[ir] doubleValue];
                _real[ir] = @([_real[k] doubleValue] - tmpr);
                _imag[ir] = @([_imag[k] doubleValue] - tmpi);
                _real[k] = @([_real[k] doubleValue] + tmpr);
                _imag[k] = @([_imag[k] doubleValue] + tmpi);
                k += j0 << 1;
            }
            j++;
        }
        j0 <<= 1;
        idx--;
    }

    for (NSInteger i = 0; i < _fftN / 2; i++) {
        double tmpr = [_real[i + 1] doubleValue];
        double tmpi = [_imag[i + 1] doubleValue];
        float value = round(sqrt(tmpr * tmpr + tmpi * tmpi));
        [outBuffer addObject:@(value)];
    }

    return outBuffer;
}
@end
