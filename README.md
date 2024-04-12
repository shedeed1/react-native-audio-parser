 # React Native Audio Parser

 React Native Audio Parser is a library designed to facilitate real-time audio parsing in React Native applications. It allows developers to analyze live audio recordings by extracting frequency data, volume levels, and channel-specific information for advanced audio processing tasks.

 ## Features

 - Real-time audio parsing.
 - Supports mono and stereo audio inputs.
 - Configurable sample rates, bits per sample, and FFT bucket counts.

 ## Installation

 Since the library is not yet published on npm, you must manually add it to your project:

 1. Clone or copy the library files into `./modules/AudioParser` in your project directory.

 2. Add the library to your `package.json`:

 ```json
"react-native-audio-parser": "link:./modules/AudioParser"
 ```

 3. Install `react-native-permissions` to handle audio recording permissions. You can find more details on the library and its usage at [react-native-permissions](https:github.com/zoontek/react-native-permissions).

 ```bash
 npm install react-native-permissions
 ```

 or with yarn:

 ```bash
 yarn add react-native-permissions
 ```

 ## Permissions

 The library requires audio recording permissions. Ensure you request the necessary permissions in your app:

 For Android, update your `AndroidManifest.xml`:

 ```xml
 <uses-permission android:name="android.permission.RECORD_AUDIO" />
 ```

 For iOS, add the following to your `Info.plist`:

 ```xml
 <key>NSMicrophoneUsageDescription</key>
 <string>We need access to your microphone for audio recording.</string>
 ```

 ## Usage

 To use the library, you need to configure the audio parser, handle permission requests, and manage the recording state.

 ### Basic Setup

 Here's a quick setup guide to integrate the audio parser in your React Native application:

 ```javascript
 import React from 'react';
 import { View, TouchableOpacity, StatusBar } from 'react-native';
 import { request, PERMISSIONS } from 'react-native-permissions';
 import AudioParser from 'react-native-audio-parser';

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
             AudioParser.stop();  Ensure no other recording is running
             AudioParser.init(audioParserConfig);
             AudioParser.start();
         }
     };

     const stopAudioRecorder = () => {
         setStarted(false);
         AudioParser.stop();
     };

     AudioParser.on(AudioParser.AudioEvent, data => {
         console.log('frequency data', data.buckets);
         console.log('volume data', data.volume);
         console.log('left channel frequency data', data.channel?.left.buckets);
      	 console.log('left channel volume data', data.channel?.left.volume);
      	 console.log('right channel frequency data', data.channel?.right.buckets);
      	 console.log('right channel volume data', data.channel?.right.volume);
     });

     return (
         <View style={{ flexDirection: 'column', justifyContent: 'center', alignItems: 'center' }}>
             <StatusBar barStyle='dark-content' />
             {started ? (
                 <TouchableOpacity onPress={stopAudioRecorder} style={{ width: 250, height: 250 }}>
                     {/* Replace with your stop icon */}
                 </TouchableOpacity>
             ) : (
                 <TouchableOpacity onPress={startAudioRecorder} style={{ flex: 1, width: 250, height: 250 }}>
                     {/* Replace with your record icon */}
                 </TouchableOpacity>
             )}
         </View>
     );
 };

 export default App;
 ```

 ## API Reference

 - `init(config: AudioParserConfig)`: Initializes the audio parser with the specified configuration.
 - `start()`: Starts the audio recording.
 - `stop()`: Stops the audio recording.
 - `on(event: string, callback: function)`: Registers a callback for audio data events.

 ### AudioParserConfig

 - `sampleRate`: Number of samples per second.
 - `channels`: Number of audio channels (1 for mono, 2 for stereo).
 - `bitsPerSample`: Bits per sample (8 or 16).
 - `bucketCount`: Number of FFT buckets (must be a power of 2).

 ## Contributing

 Contributions to the React Native Audio Parser are welcome. Please ensure to follow the existing code style and add unit tests for any new or changed functionality.

 ## License

 Specify your license here, e.g., MIT.
