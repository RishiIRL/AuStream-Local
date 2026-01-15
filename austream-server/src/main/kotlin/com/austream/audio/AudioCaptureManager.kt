package com.austream.audio

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.austream.model.AudioApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.sound.sampled.*
import javax.swing.filechooser.FileSystemView

/**
 * Manages audio capture from Windows using WASAPI loopback via PowerShell.
 * This captures system audio output without requiring Stereo Mix or third-party software.
 */
class AudioCaptureManager {
    
    private val _audioFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 100)
    val audioFlow: SharedFlow<ByteArray> = _audioFlow
    
    private var captureJob: Job? = null
    private var wasapiProcess: Process? = null
    private var isCapturing = false
    
    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val BITS_PER_SAMPLE = 16
        const val FRAME_SIZE_MS = 10
        const val BYTES_PER_FRAME = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8) * FRAME_SIZE_MS / 1000
    }
    
    /**
     * Get list of applications that can produce audio.
     * Excludes known non-audio apps like file managers, text editors, settings, etc.
     */
    fun getAudioApplications(): List<AudioApplication> {
        return try {
            val process = ProcessBuilder(
                "powershell", "-Command",
                """
                ${'$'}excludeApps = @(
                    'explorer', 'notepad', 'notepad++', 'Code', 'devenv', 'idea64',
                    'cmd', 'powershell', 'WindowsTerminal', 'conhost',
                    'SystemSettings', 'ApplicationFrameHost', 'TextInputHost',
                    'mmc', 'taskmgr', 'perfmon', 'regedit', 'mspaint',
                    'Calculator', 'SnippingTool', 'mstsc', 'Magnify',
                    'WINWORD', 'EXCEL', 'POWERPNT', 'OUTLOOK', 'ONENOTE',
                    'acrobat', 'AcroRd32', 'FoxitReader',
                    'SearchHost', 'StartMenuExperienceHost', 'ShellExperienceHost',
                    'FileExplorer', 'Widgets', 'WidgetService'
                )
                Get-Process | Where-Object { 
                    ${'$'}_.MainWindowTitle -ne '' -and 
                    ${'$'}excludeApps -notcontains ${'$'}_.ProcessName -and
                    ${'$'}_.ProcessName -notmatch '^(svchost|csrss|dwm|sihost|fontdrvhost|ctfmon|RuntimeBroker)$'
                } | Select-Object Id, ProcessName, MainWindowTitle, Path | ConvertTo-Json
                """.trimIndent()
            ).start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            reader.close()
            process.waitFor()
            
            parseProcessList(output)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    private fun parseProcessList(json: String): List<AudioApplication> {
        if (json.isBlank()) return emptyList()
        
        val apps = mutableListOf<AudioApplication>()
        val pattern = """"Id"\s*:\s*(\d+).*?"ProcessName"\s*:\s*"([^"]+)".*?"MainWindowTitle"\s*:\s*"([^"]*)".*?"Path"\s*:\s*"?([^",}\]]*)"?""".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        pattern.findAll(json).forEach { match ->
            val (id, name, title, path) = match.destructured
            val displayName = if (title.isNotBlank()) title else name
            val cleanPath = path.replace("\\\\", "\\")
            val icon = extractIcon(cleanPath)
            
            apps.add(AudioApplication(
                processId = id.toInt(),
                name = displayName,
                executablePath = cleanPath,
                icon = icon
            ))
        }
        
        return apps.distinctBy { it.processId }
    }
    
    /**
     * Extract icon from an executable file
     */
    private fun extractIcon(executablePath: String): ImageBitmap? {
        return try {
            if (executablePath.isBlank()) return null
            val file = File(executablePath)
            if (!file.exists()) return null
            
            val icon = FileSystemView.getFileSystemView().getSystemIcon(file, 32, 32)
            if (icon != null) {
                val bufferedImage = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
                val g = bufferedImage.createGraphics()
                icon.paintIcon(null, g, 0, 0)
                g.dispose()
                bufferedImage.toComposeImageBitmap()
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Start capturing system audio using WASAPI loopback.
     */
    fun startCapture(scope: CoroutineScope, targetApp: AudioApplication? = null) {
        if (isCapturing) return
        isCapturing = true
        
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                captureWithWasapiLoopback()
            } catch (_: Exception) {
                // Fallback to test audio if WASAPI fails
                generateTestAudio()
            }
        }
    }
    
    /**
     * Capture system audio using WASAPI loopback via PowerShell/C#.
     * This runs inline C# code that uses Windows Core Audio APIs.
     */
    private suspend fun captureWithWasapiLoopback() {
        
        // PowerShell script with inline C# for WASAPI loopback
        val psScript = """
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Threading;

public class WasapiLoopback {
    [DllImport("ole32.dll")]
    static extern int CoInitializeEx(IntPtr pvReserved, int dwCoInit);
    
    [DllImport("ole32.dll")]
    static extern void CoUninitialize();
    
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumerator { }
    
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator {
        int NotImpl1();
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }
    
    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice {
        int Activate([MarshalAs(UnmanagedType.LPStruct)] Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
    }
    
    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient {
        int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr pFormat, IntPtr audioSessionGuid);
        int GetBufferSize(out uint bufferSize);
        int GetStreamLatency(out long latency);
        int GetCurrentPadding(out uint padding);
        int IsFormatSupported(int shareMode, IntPtr pFormat, out IntPtr closestMatch);
        int GetMixFormat(out IntPtr format);
        int GetDevicePeriod(out long defaultPeriod, out long minPeriod);
        int Start();
        int Stop();
        int Reset();
        int SetEventHandle(IntPtr handle);
        int GetService([MarshalAs(UnmanagedType.LPStruct)] Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }
    
    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient {
        int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        int ReleaseBuffer(uint frames);
        int GetNextPacketSize(out uint frames);
    }
    
    [StructLayout(LayoutKind.Sequential)]
    struct WAVEFORMATEX {
        public ushort wFormatTag;
        public ushort nChannels;
        public uint nSamplesPerSec;
        public uint nAvgBytesPerSec;
        public ushort nBlockAlign;
        public ushort wBitsPerSample;
        public ushort cbSize;
    }
    
    public static void Capture() {
        CoInitializeEx(IntPtr.Zero, 0);
        try {
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
            IMMDevice device;
            enumerator.GetDefaultAudioEndpoint(0, 0, out device);
            
            object audioClientObj;
            device.Activate(new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), 23, IntPtr.Zero, out audioClientObj);
            var audioClient = (IAudioClient)audioClientObj;
            
            IntPtr formatPtr;
            audioClient.GetMixFormat(out formatPtr);
            var format = Marshal.PtrToStructure<WAVEFORMATEX>(formatPtr);
            
            Console.Error.WriteLine("FORMAT:" + format.nSamplesPerSec + ":" + format.nChannels + ":" + format.wBitsPerSample);
            
            int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
            audioClient.Initialize(0, AUDCLNT_STREAMFLAGS_LOOPBACK, 10000000, 0, formatPtr, IntPtr.Zero);
            
            object captureClientObj;
            audioClient.GetService(new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), out captureClientObj);
            var captureClient = (IAudioCaptureClient)captureClientObj;
            
            audioClient.Start();
            Console.Error.WriteLine("STARTED");
            
            int bytesPerFrame = format.nChannels * (format.wBitsPerSample / 8);
            var stdout = Console.OpenStandardOutput();
            
            while (true) {
                uint packetSize;
                captureClient.GetNextPacketSize(out packetSize);
                
                while (packetSize > 0) {
                    IntPtr dataPtr;
                    uint framesAvailable, flags;
                    ulong devicePos, qpcPos;
                    
                    captureClient.GetBuffer(out dataPtr, out framesAvailable, out flags, out devicePos, out qpcPos);
                    
                    if (framesAvailable > 0) {
                        int numBytes = (int)(framesAvailable * bytesPerFrame);
                        byte[] buffer = new byte[numBytes];
                        Marshal.Copy(dataPtr, buffer, 0, numBytes);
                        
                        // Convert 32-bit float to 16-bit PCM if needed
                        if (format.wBitsPerSample == 32) {
                            byte[] pcm16 = new byte[numBytes / 2];
                            for (int i = 0; i < numBytes / 4; i++) {
                                float sample = BitConverter.ToSingle(buffer, i * 4);
                                short pcmSample = (short)(sample * 32767);
                                pcm16[i * 2] = (byte)(pcmSample & 0xFF);
                                pcm16[i * 2 + 1] = (byte)((pcmSample >> 8) & 0xFF);
                            }
                            stdout.Write(pcm16, 0, pcm16.Length);
                        } else {
                            stdout.Write(buffer, 0, numBytes);
                        }
                        stdout.Flush();
                    }
                    
                    captureClient.ReleaseBuffer(framesAvailable);
                    captureClient.GetNextPacketSize(out packetSize);
                }
                
                Thread.Sleep(5);
            }
        } finally {
            CoUninitialize();
        }
    }
}
"@ -Language CSharp

[WasapiLoopback]::Capture()
"""
        
        wasapiProcess = ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "-")
            .redirectErrorStream(false)
            .start()
        
        // Send the script to PowerShell's stdin
        wasapiProcess!!.outputStream.bufferedWriter().use { writer ->
            writer.write(psScript)
        }
        
        // Read audio data from stdout
        val inputStream = wasapiProcess!!.inputStream
        val errorStream = wasapiProcess!!.errorStream
        
        // Read stderr (status messages - discarded in production)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            errorStream.bufferedReader().forEachLine { }
        }
        
        val buffer = ByteArray(BYTES_PER_FRAME)
        var offset = 0
        
        while (isCapturing && wasapiProcess?.isAlive == true) {
            val bytesRead = inputStream.read(buffer, offset, buffer.size - offset)
            if (bytesRead == -1) break
            
            offset += bytesRead
            
            // When we have a full frame, emit it
            if (offset >= BYTES_PER_FRAME) {
                _audioFlow.emit(buffer.copyOf())
                offset = 0
            }
        }
        
        wasapiProcess?.destroyForcibly()
    }
    
    /**
     * Generate test audio (sine wave) for testing
     */
    private suspend fun generateTestAudio() {
        val frequency = 440.0
        var phase = 0.0
        val phaseIncrement = 2.0 * Math.PI * frequency / SAMPLE_RATE
        
        while (isCapturing) {
            val buffer = ByteArray(BYTES_PER_FRAME)
            
            for (i in 0 until BYTES_PER_FRAME step 4) {
                val sample = (Short.MAX_VALUE * 0.3 * Math.sin(phase)).toInt().toShort()
                
                buffer[i] = (sample.toInt() and 0xFF).toByte()
                buffer[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                buffer[i + 2] = (sample.toInt() and 0xFF).toByte()
                buffer[i + 3] = ((sample.toInt() shr 8) and 0xFF).toByte()
                
                phase += phaseIncrement
                if (phase >= 2.0 * Math.PI) phase -= 2.0 * Math.PI
            }
            
            _audioFlow.emit(buffer)
            delay(FRAME_SIZE_MS.toLong())
        }
    }
    
    fun stopCapture() {
        isCapturing = false
        wasapiProcess?.destroyForcibly()
        wasapiProcess = null
        captureJob?.cancel()
        captureJob = null
    }
    
    fun isCapturing(): Boolean = isCapturing
}
