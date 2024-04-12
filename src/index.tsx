import { NativeEventEmitter, NativeModules } from 'react-native';

type Channel = 1 | 2;
type BitsPerSample = 8 | 16;

const EventEmitter = new NativeEventEmitter(NativeModules.AudioParser);

export interface AudioParserConfig {
	/**
   * Represents how many samples to take in a second
   */
	sampleRate: number;

	/**
   * Represents channel, can be 1 for MONO or 2 for STEREO
   */
	channels: Channel;
	/**
   * Represents how many bits to take per sample (can be 8 or 16)
   */
	bitsPerSample: BitsPerSample;
	/**
   * Represents how many buckets to capture for grouping per sample for FFT, must be a power of 2
   */
	bucketCount: number;
}

interface AudioEqualizerData {
	/**
   * Volume is a number representing the total volume of the audio chunk
   */
	volume: number;

	/**
   * This should be an array of integers for each frequency bucket
   * Each item should be in the same format as the volume
   * It should have the same number of items from bucketCount
   */
	buckets: number[];

	/**
   * Channel data, only available for stereo audio
   */
	channel?: {
		left: {
			volume: number;
			buckets: number[];
		};
		right: {
			volume: number;
			buckets: number[];
		};
	};
}

interface AudioParser {
	/**
   * Initialize the audio recorder
   * @param config
   */
	init: (config: AudioParserConfig) => void;

	/**
   * Start the audio recording
   */
	start: () => void;

	/**
   * Stop the audio recording
   */
	stop: () => void;

	/**
   * Get the audio equalizer data
   */
	on: (event: string, callback: (data: AudioEqualizerData) => void) => void;

	AudioEvent: string;
}

const eventsMap: {
	[key: string]: string;
} = {
	audioData: 'audioData'
};

export default {
	...NativeModules.AudioParser,
	on: (event: keyof typeof eventsMap, callback: (data: string) => void) => {
		const nativeEvent = eventsMap[event];
		if (!nativeEvent) {
			throw new Error('Invalid event');
		}
		EventEmitter.removeAllListeners(nativeEvent);
		return(EventEmitter.addListener(nativeEvent, callback));
	},
	AudioEvent: 'audioData'
} as AudioParser;
