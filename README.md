# React Native Audio Parser

React Native Audio Parser is a versatile library designed to facilitate real-time and file-based audio parsing in React
Native applications. It enables developers to analyze audio recordings or live audio inputs by extracting frequency
data, volume levels, and channel-specific information, which can be vital for advanced audio processing tasks.

## Features

- Real-time and file-based audio parsing.
- Supports mono and stereo audio inputs.
- Configurable sample rates, bits per sample, and FFT bucket counts.
- Ability to parse audio data from live input or read from files.

## Installation

Since the library is not yet published on npm, you must manually add it to your project:

1. Clone or copy the library files into `./modules/AudioParser` in your project directory.

2. Add the library to your `package.json`:

 ```json
 "react-native-audio-parser": "link:./modules/AudioParser"
 ```

3. Install `react-native-permissions` and `react-native-document-picker` to handle permissions and file access:

 ```bash
 npm install react-native-permissions react-native-document-picker
 ```

or with yarn:

 ```bash
 yarn add react-native-permissions react-native-document-picker
 ```

## Permissions

The library requires audio recording permissions and file access permissions. Ensure you request the necessary
permissions in your app:

For Android, update your `AndroidManifest.xml`:

 ```xml

<uses-permission android:name="android.permission.RECORD_AUDIO"/>
 ```

For iOS, add the following to your `Info.plist`:

 ```xml

<key>NSMicrophoneUsageDescription</key>
<string>We need access to your microphone for audio recording.</string>
 ```

## Usage

To use the library, configure the audio parser for either live recording or file reading, handle permission requests,
and manage the recording or file reading state.

### Basic Setup

Here's a quick setup guide to integrate the audio parser in your React Native application for both live recording and
file reading:

 ```javascript
 import React from 'react';
import {View, TouchableOpacity, StatusBar} from 'react-native';
import {request, PERMISSIONS} from 'react-native-permissions';
import AudioParser from 'react-native-audio-parser';
import DocumentPicker from 'react-native-document-picker';

const App = () => {
  const [started, setStarted] = React.useState(false);
  const audioParserConfig = {
    sampleRate: 44100,
    channels: 2,
    bitsPerSample: 16,
    bucketCount: 512
  };

  const startAudioRecorder = async () => {
    const status = await request(
      Platform.select({
        android: PERMISSIONS.ANDROID.RECORD_AUDIO,
        ios: PERMISSIONS.IOS.MICROPHONE,
      }),
    );
    if (status === 'granted') {
      setStarted(true);
      // Ensure no other recording is in progress
      AudioParser.stop();
      AudioParser.init(audioParserConfig);
      AudioParser.start();
    }
  };

  const stopAudioRecorder = () => {
    setStarted(false);
    AudioParser.stop();
  };

  const startFileReading = async () => {
    try {
      const [pickResult] = await pick();
      AudioParser.startFromFile(pickResult.uri);
    } catch (err) {
      if (DocumentPicker.isCancel(err)) {
        // User canceled the picker
      } else {
        throw err;
      }
    }
  };

  const stopFileReading = () => {
    AudioParser.stopReadingFile();
  };

  useEffect(() => {
    AudioParser.onRecording(AudioParser.RecordingData, data => {
      console.log('Live frequency data', data.buckets);
      console.log('Live volume data', data.volume);
    });

    AudioParser.onFileRead(AudioParser.FileData, data => {
      console.log('File read percentage', data.percentageRead);
      console.log('File frequency data', data.buckets);
      console.log('File volume data', data.volume);
    });

    return () => {
      AudioParser.unregisterAll();
    };
  }, []);


  return (
    <View style={{flexDirection: 'column', justifyContent: 'center', alignItems: 'center'}}>
      <StatusBar barStyle='dark-content'/>
      {started ? (
        <TouchableOpacity onPress={stopAudioRecorder} style={{width: 250, height: 250}}>
          {/* Replace with your stop icon */}
        </TouchableOpacity>
      ) : (
        <TouchableOpacity onPress={startAudioRecorder} style={{flex: 1, width: 250, height: 250}}>
          {/* Replace with your record icon */}
        </TouchableOpacity>
      )}
      <TouchableOpacity onPress={startFileReading} style={{width: 250, height: 50}}>
        {/* Replace with your file reading icon */}
      </TouchableOpacity>
    </View>
  );
};

export default App;
 ```

## API Reference

- `init(config: AudioParserConfig)`: Initializes the audio parser with the specified configuration. Returns nothing.
    - `AudioParserConfig` includes:
        - `sampleRate`: Number of samples per second.
        - `channels`: Number of audio channels (1 for mono, 2 for stereo).
        - `bitsPerSample`: Number of bits per sample (8 or 16).
        - `bucketCount`: Number of FFT buckets (must be a power of 2).

- `start()`: Starts the audio recording. Returns nothing.

- `stop()`: Stops the audio recording. Returns nothing.

- `startFromFile(uri: string)`: Starts reading audio chunks from a WAV file, and emits back AudioEqualizerData. Returns
  nothing.

- `stopReadingFile()`: Stops reading audio chunks from a WAV file. Returns nothing.

- `onRecording(event: string, callback: function)`: Registers a callback for live audio data events. Returns the
  registration ID for the event listener.
    - `RecordingEqualizerData` provided to the callback includes:
        - `volume`: Peak volume of the live audio chunk.
        - `buckets`: Array of integers representing the amplitude for each frequency bucket.
        - `channel`: Optional object for stereo inputs:
            - `left`: { volume: number, buckets: number[] }
            - `right`: { volume: number, buckets: number[] }
        - `rawBuffer`: Raw PCM data as a number array. Format depends on `bitsPerSample`.

- `onFileRead(event: string, callback: function)`: Registers a callback for file-based audio data events. Returns the
  registration ID for the event listener.
    - `FileEqualizerData` provided to the callback includes:
        - `volume`: Peak volume of the audio chunk from the file.
        - `buckets`: Array of integers representing the amplitude for each frequency bucket.
        - `channel`: Optional object for stereo inputs:
            - `left`: { volume: number, buckets: number[] }
            - `right`: { volume: number, buckets: number[] }
        - `rawBuffer`: Raw PCM data as a number array. Format depends on `bitsPerSample`.
        - `percentageRead`: Represents the percentage of the file that has been read, returns 100 when the file has been
          fully read.

## Contributing

Contributions to the React Native Audio Parser are welcome. Please ensure to follow the existing code style and add unit
tests for any new or changed functionality.

## License

Specify your license here, e.g., MIT.
