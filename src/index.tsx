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

interface EqualizerData {
	/**
   * Volume is a number representing the peak volume of the audio chunk
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

interface RecordingEqualizerData extends EqualizerData {
	/**
   * Represents raw buffer data, this needs to be converted back to 8-bit or 16-bit PCM data
   * The raw audio data emitted will be either in the form of ByteArray (for 8-bit samples) or ShortArray (for 16-bit samples). These data formats are transferred as arrays of integers to React Native.
   * You'll need to ensure that the data is correctly interpreted on the JavaScript side.
   * Example: new Uint8Array(buffer)
   */
	rawBuffer: number[];
}

interface FileEqualizerData extends RecordingEqualizerData {
	/**
   * Represents the percentage of the file that has been read, returns 100 when the file has been fully read
   */
	percentageRead: number;
}

interface AudioParser {
	/**
   * Initialize the audio parser instance
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
   * Start reading audio chunks from a WAV file, and emit back AudioEqualizerData
   */
	startFromFile: (uri: string) => void;
	/**
   * Stop reading audio chunks from a WAV file
   */
	stopReadingFile: () => void;

	/**
   * Get the audio equalizer data
   */
	onRecording: (
		event: string,
		callback: (data: RecordingEqualizerData) => void,
	) => void;
	/**
   * Get the audio equalizer data
   */
	onFileRead: (
		event: string,
		callback: (data: FileEqualizerData) => void,
	) => void;

	RecordingData: string;
	FileData: string;
	unregisterAll: () => void;
}

const eventsMap: {
	[key: string]: string;
} = {
	RecordingData: 'RecordingData',
	FileData: 'FileData'
};

export default {
	...NativeModules.AudioParser,
	onFileRead: function(
		event: keyof typeof eventsMap,
		callback: (data: string) => void,
	) {
		const nativeEvent = eventsMap[event];
		if (!nativeEvent) {
			throw new Error('Invalid event');
		}
		EventEmitter.removeAllListeners(nativeEvent);
		return(EventEmitter.addListener(nativeEvent, callback));
	},
	onRecording: function(
		event: keyof typeof eventsMap,
		callback: (data: string) => void,
	) {
		const nativeEvent = eventsMap[event];
		if (!nativeEvent) {
			throw new Error('Invalid event');
		}
		EventEmitter.removeAllListeners(nativeEvent);
		return(EventEmitter.addListener(nativeEvent, callback));
	},
	unregisterAll: function() {
		EventEmitter.removeAllListeners(eventsMap.RecordingData);
		EventEmitter.removeAllListeners(eventsMap.FileData);
	},
	RecordingData: 'RecordingData',
	FileData: 'FileData'
} as AudioParser;
