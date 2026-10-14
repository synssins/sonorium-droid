## Device test, Android API 35: failure

Server: 1.7.3-dev.64

### Server view

Linked phones: 0
No phone linked.

### Instrumentation
```
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS: class=app.sonorium.Mp3Benchmark
INSTRUMENTATION_STATUS: current=37
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=37
INSTRUMENTATION_STATUS: stream=
INSTRUMENTATION_STATUS: test=liveMixCost
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: stream=Mp3Benchmark: live mix, 5 tracks (stereo, 320 kbps MP3 read live): 20 s mixed in 3880 ms, process cpu 2919 ms; cpu per playback hour ≈ 525 s (14.6 % of one core); files open at once: 8

INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS: stream=Mp3Benchmark: live mix, 10 tracks (stereo, 320 kbps MP3 read live): 20 s mixed in 7334 ms, process cpu 5539 ms; cpu per playback hour ≈ 997 s (27.7 % of one core); files open at once: 13

INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS: stream=Mp3Benchmark: live mix, 20 tracks (stereo, 320 kbps MP3 read live): 20 s mixed in 14844 ms, process cpu 10954 ms; cpu per playback hour ≈ 1972 s (54.8 % of one core); files open at once: 23

INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS: class=app.sonorium.Mp3Benchmark
INSTRUMENTATION_STATUS: current=37
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=37
INSTRUMENTATION_STATUS: stream=.
INSTRUMENTATION_STATUS: test=liveMixCost
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=

Time: 448.381
There was 1 failure:
1) linksAndControlsTheServerNatively(app.sonorium.EndToEndTest)
java.lang.AssertionError: Linking didn't finish; see 01-linked.png
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.assertTrue(Assert.java:42)
	at org.junit.Assert.assertNotNull(Assert.java:713)
	at app.sonorium.EndToEndTest.linksAndControlsTheServerNatively(EndToEndTest.kt:73)

FAILURES!!!
Tests run: 37,  Failures: 1


INSTRUMENTATION_CODE: -1
```
