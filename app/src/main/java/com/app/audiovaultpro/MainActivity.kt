package com.app.audiovaultpro

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import java.util.UUID

// Data Classes
data class AudioFile(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val format: String,
    val size: Long
)

data class RecordingSession(
    val id: String,
    val filename: String,
    val duration: Long,
    val format: AudioFormat,
    val timestamp: Long,
    val path: String
)

enum class AudioFormat(val extension: String, val displayName: String) {
    MP3("mp3", "MP3"),
    FLAC("flac", "FLAC"),
    OGG("ogg", "OGG Vbs"),
    M4A("m4a", "M4A"),
    WAV("wav", "WAV")
}

enum class PlayerState {
    IDLE, PLAYING, PAUSED, RECORDING
}

// Audio Processing Classes
class AudioProcessor {
    // Simple EQ implementation
    fun applyEqualizer(audioData: FloatArray, bands: FloatArray): FloatArray {
        val processed = audioData.copyOf()
        // Apply basic EQ curves (simplified implementation)
        for (i in processed.indices) {
            var sample = processed[i]
            // Apply frequency-based adjustments
            bands.forEachIndexed { bandIndex, gain ->
                val frequency = 31.25 * (2.0.pow(bandIndex)).toFloat()
                val factor = 1.0f + (gain / 100.0f)
                sample *= factor
            }
            processed[i] = sample.coerceIn(-1.0f, 1.0f)
        }
        return processed
    }

    // Noise reduction using spectral subtraction
    fun reduceNoise(audioData: FloatArray, noiseLevel: Float = 0.1f): FloatArray {
        val processed = audioData.copyOf()
        val alpha = 2.0f // Over-subtraction factor

        for (i in processed.indices) {
            val magnitude = abs(processed[i])
            if (magnitude > noiseLevel) {
                val reducedMagnitude = magnitude - alpha * noiseLevel
                processed[i] = if (reducedMagnitude > 0) {
                    reducedMagnitude * sign(processed[i])
                } else {
                    processed[i] * 0.1f // Preserve some residual
                }
            }
        }
        return processed
    }

    // Dynamic range compression
    fun compress(audioData: FloatArray, threshold: Float = 0.7f, ratio: Float = 4.0f): FloatArray {
        val processed = audioData.copyOf()

        for (i in processed.indices) {
            val magnitude = abs(processed[i])
            if (magnitude > threshold) {
                val excess = magnitude - threshold
                val compressedExcess = excess / ratio
                val newMagnitude = threshold + compressedExcess
                processed[i] = newMagnitude * sign(processed[i])
            }
        }
        return processed
    }
}

// Main ViewModel
class AudioViewModel : ViewModel() {
    private val _audioFiles = MutableStateFlow<List<AudioFile>>(emptyList())
    val audioFiles: StateFlow<List<AudioFile>> = _audioFiles.asStateFlow()

    private val _currentFile = MutableStateFlow<AudioFile?>(null)
    val currentFile: StateFlow<AudioFile?> = _currentFile.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState.IDLE)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _recordings = MutableStateFlow<List<RecordingSession>>(emptyList())
    val recordings: StateFlow<List<RecordingSession>> = _recordings.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private val _equalizerBands = MutableStateFlow(FloatArray(10) { 0f })
    val equalizerBands: StateFlow<FloatArray> = _equalizerBands.asStateFlow()

    private val _noiseReduction = MutableStateFlow(false)
    val noiseReduction: StateFlow<Boolean> = _noiseReduction.asStateFlow()

    private val _compression = MutableStateFlow(false)
    val compression: StateFlow<Boolean> = _compression.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioProcessor = AudioProcessor()
    private var recordingJob: Job? = null

    private val _currentRecordingPath = MutableStateFlow<String?>(null)
    private val _currentRecordingFormat = MutableStateFlow<AudioFormat?>(null)

    // Settings state for effects
    private val _recordingEffectsEnabled = MutableStateFlow(false)
    val recordingEffectsEnabled: StateFlow<Boolean> = _recordingEffectsEnabled.asStateFlow()

    // Method to toggle recording effects
    fun toggleRecordingEffects() {
        _recordingEffectsEnabled.value = !_recordingEffectsEnabled.value
    }

    fun loadAudioFiles(files: List<AudioFile>) {
        _audioFiles.value = files
    }

    fun playFile(file: AudioFile) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.path)
                prepareAsync()
                setOnPreparedListener {
                    start()
                    _playerState.value = PlayerState.PLAYING
                    _currentFile.value = file
                    startPositionUpdates()
                }
                setOnCompletionListener {
                    _playerState.value = PlayerState.IDLE
                    _currentPosition.value = 0L
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pausePlayback() {
        mediaPlayer?.pause()
        _playerState.value = PlayerState.PAUSED
    }

    fun resumePlayback() {
        mediaPlayer?.start()
        _playerState.value = PlayerState.PLAYING
    }

    fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _playerState.value = PlayerState.IDLE
        _currentPosition.value = 0L
    }

    fun seekTo(position: Long) {
        mediaPlayer?.seekTo(position.toInt())
        _currentPosition.value = position
    }

    private fun startPositionUpdates() {
        viewModelScope.launch {
            while (_playerState.value == PlayerState.PLAYING) {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        _currentPosition.value = it.currentPosition.toLong()
                    }
                }
                delay(1000)
            }
        }
    }

    fun playNext() {
        val currentIndex = _audioFiles.value.indexOfFirst { it.id == _currentFile.value?.id }
        if (currentIndex != -1 && currentIndex < _audioFiles.value.size - 1) {
            val nextFile = _audioFiles.value[currentIndex + 1]
            playFile(nextFile)
        }
    }

    fun playPrevious() {
        val currentIndex = _audioFiles.value.indexOfFirst { it.id == _currentFile.value?.id }
        if (currentIndex > 0) {
            val previousFile = _audioFiles.value[currentIndex - 1]
            playFile(previousFile)
        }
    }

    // Add this property to store the current recording file
    private var currentRecordingFile: File? = null

    // Updated startRecording method to include effects processing
    fun startRecording(outputFile: File, format: AudioFormat) {
        try {
            currentRecordingFile = outputFile

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(when (format) {
                    AudioFormat.MP3 -> MediaRecorder.OutputFormat.MPEG_4
                    AudioFormat.M4A -> MediaRecorder.OutputFormat.MPEG_4
                    AudioFormat.OGG -> MediaRecorder.OutputFormat.OGG
                    AudioFormat.WAV -> MediaRecorder.OutputFormat.THREE_GPP
                    else -> MediaRecorder.OutputFormat.DEFAULT
                })
                setAudioEncoder(when (format) {
                    AudioFormat.MP3 -> MediaRecorder.AudioEncoder.AAC
                    AudioFormat.M4A -> MediaRecorder.AudioEncoder.AAC
                    AudioFormat.OGG -> MediaRecorder.AudioEncoder.VORBIS
                    AudioFormat.WAV -> MediaRecorder.AudioEncoder.AMR_NB
                    else -> MediaRecorder.AudioEncoder.DEFAULT
                })

                // Apply enhanced settings when effects are enabled
                if (_recordingEffectsEnabled.value) {
                    setAudioEncodingBitRate(320000) // High quality
                    setAudioSamplingRate(48000) // Higher sample rate

                    // Apply noise suppression if available (Android 7.0+)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        try {
                            // Enable noise suppression
                            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        } catch (e: Exception) {
                            // Fallback to MIC if VOICE_RECOGNITION not available
                            setAudioSource(MediaRecorder.AudioSource.MIC)
                        }
                    }
                } else {
                    setAudioEncodingBitRate(128000) // Standard quality
                    setAudioSamplingRate(44100)
                }

                setOutputFile(outputFile.absolutePath)
                _currentRecordingPath.value = outputFile.absolutePath
                _currentRecordingFormat.value = format
                prepare()
                start()
            }

            _isRecording.value = true
            _playerState.value = PlayerState.RECORDING
            startRecordingTimer()

        } catch (e: Exception) {
            e.printStackTrace()
            currentRecordingFile = null
        }
    }

    fun stopRecording(): File? {
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            _isRecording.value = false
            _playerState.value = PlayerState.IDLE
            recordingJob?.cancel()

            val recordedFile = currentRecordingFile
            val recordingPath = _currentRecordingPath.value
            val recordingFormat = _currentRecordingFormat.value

            // Create recording session and add to list
            if (recordedFile != null && recordingPath != null && recordingFormat != null) {
                val recordingSession = RecordingSession(
                    id = UUID.randomUUID().toString(),
                    filename = recordedFile.name,
                    duration = _recordingDuration.value,
                    format = recordingFormat,
                    timestamp = System.currentTimeMillis(),
                    path = recordingPath
                )

                // Add to recordings list
                val currentRecordings = _recordings.value.toMutableList()
                currentRecordings.add(0, recordingSession) // Add at beginning
                _recordings.value = currentRecordings
            }

            _recordingDuration.value = 0L
            currentRecordingFile = null
            _currentRecordingPath.value = null
            _currentRecordingFormat.value = null

            recordedFile
        } catch (e: Exception) {
            e.printStackTrace()
            currentRecordingFile = null
            _currentRecordingPath.value = null
            _currentRecordingFormat.value = null
            null
        }
    }

    private fun startRecordingTimer() {
        recordingJob = viewModelScope.launch {
            var duration = 0L
            while (_isRecording.value) {
                _recordingDuration.value = duration
                delay(1000)
                duration += 1000
            }
        }
    }

    fun updateEqualizerBand(bandIndex: Int, value: Float) {
        val bands = _equalizerBands.value.copyOf()
        bands[bandIndex] = value
        _equalizerBands.value = bands
    }

    fun toggleNoiseReduction() {
        _noiseReduction.value = !_noiseReduction.value
    }

    fun toggleCompression() {
        _compression.value = !_compression.value
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        mediaRecorder?.release()
        recordingJob?.cancel()
    }
}

// Composable Functions
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioVaultApp() {
    val viewModel: AudioViewModel = viewModel()
    var selectedTab by remember { mutableStateOf(0) }
    var showAppInfo by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) } // New settings dialog
    val context = LocalContext.current

    // Permission handling (keep existing code)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val storageGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
                permissions[Manifest.permission.READ_MEDIA_AUDIO] == true

        if (storageGranted) {
            val audioFiles = AudioFileManager.scanAudioFiles(context)
            viewModel.loadAudioFiles(audioFiles)
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    LaunchedEffect(Unit) {
        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (hasPermission) {
            val audioFiles = AudioFileManager.scanAudioFiles(context)
            viewModel.loadAudioFiles(audioFiles)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9C27B0),
            secondary = Color(0xFF673AB7),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1A1A1A),
                            Color(0xFF0A0A0A)
                        )
                    )
                )
        ) {
            // Top App Bar with Info and Settings Button
            TopAppBar(
                title = {
                    Text(
                        "AudioVault Pro",
                        fontSize = 20.sp, // Reduced for smaller screens
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                actions = {
                    // Settings Button (replaces Effects tab)
                    IconButton(
                        onClick = { showSettings = true }
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Audio Settings",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = { showAppInfo = true }
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "App Info",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )

            // Tab Navigation (only Player and Recorder now)
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = Color.White
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Player") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Recorder") }
                )
            }

            // Content based on selected tab
            when (selectedTab) {
                0 -> PlayerScreen(viewModel)
                1 -> RecorderScreen(viewModel)
            }
        }

        // App Info Dialog
        if (showAppInfo) {
            AppInfoDialog(onDismiss = { showAppInfo = false })
        }

        // Settings Dialog (Effects moved here)
        if (showSettings) {
            AudioSettingsDialog(
                viewModel = viewModel,
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
fun AppInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color(0xFF9C27B0),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "AudioVault Pro",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        },
        text = {
            Column {
                Text(
                    "Version: 1.0.0",
                    fontSize = 16.sp,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    "Professional audio recording and playback application with advanced audio processing capabilities.",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Divider(color = Color(0xFF333333))

                Text(
                    "Developer Information",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Text(
                    "Developed by: Marc Freir",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Text(
                    "\u00A9 Quarks Dev (2025)",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Text(
                    "Contact: markfreir@google.com",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Text(
                    "Website: https:// marcfreir.github.io",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF9C27B0)
                )
            ) {
                Text("Close")
            }
        },
        containerColor = Color(0xFF2A2A2A),
        textContentColor = Color.White
    )
}

@Composable
fun AudioSettingsDialog(
    viewModel: AudioViewModel,
    onDismiss: () -> Unit
) {
    val equalizerBands by viewModel.equalizerBands.collectAsState()
    val noiseReduction by viewModel.noiseReduction.collectAsState()
    val compression by viewModel.compression.collectAsState()
    val recordingEffectsEnabled by viewModel.recordingEffectsEnabled.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = Color(0xFF9C27B0),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Audio Settings",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.height(400.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    // Recording Effects Toggle
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF333333))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "Recording Effects",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        "Apply effects during recording",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                                Switch(
                                    checked = recordingEffectsEnabled,
                                    onCheckedChange = { viewModel.toggleRecordingEffects() },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF9C27B0)
                                    )
                                )
                            }
                        }
                    }
                }

                item {
                    // Audio Processing
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF333333))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Audio Processing",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            // Noise Reduction
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Noise Reduction", fontSize = 12.sp, color = Color.White)
                                    Text("Reduce background noise", fontSize = 10.sp, color = Color.Gray)
                                }
                                Switch(
                                    checked = noiseReduction,
                                    onCheckedChange = { viewModel.toggleNoiseReduction() },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF9C27B0)
                                    )
                                )
                            }

                            // Compression
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Compression", fontSize = 12.sp, color = Color.White)
                                    Text("Dynamic range control", fontSize = 10.sp, color = Color.Gray)
                                }
                                Switch(
                                    checked = compression,
                                    onCheckedChange = { viewModel.toggleCompression() },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF9C27B0)
                                    )
                                )
                            }
                        }
                    }
                }

                item {
                    // Compact Equalizer
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF333333))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Equalizer",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                val frequencies = arrayOf("31", "125", "500", "2K", "8K")
                                val indices = arrayOf(0, 2, 4, 6, 8) // Sample 5 bands

                                frequencies.forEachIndexed { index, freq ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            "${equalizerBands[indices[index]].toInt()}",
                                            fontSize = 8.sp,
                                            color = Color.Gray
                                        )

                                        Slider(
                                            value = equalizerBands[indices[index]],
                                            onValueChange = { viewModel.updateEqualizerBand(indices[index], it) },
                                            valueRange = -12f..12f,
                                            modifier = Modifier
                                                .height(80.dp)
                                                .width(20.dp),
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color(0xFF9C27B0),
                                                activeTrackColor = Color(0xFF9C27B0)
                                            )
                                        )

                                        Text(
                                            freq,
                                            fontSize = 8.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF9C27B0)
                )
            ) {
                Text("Done")
            }
        },
        containerColor = Color(0xFF2A2A2A),
        textContentColor = Color.White
    )
}

@Composable
fun PlayerScreen(viewModel: AudioViewModel) {
    val audioFiles by viewModel.audioFiles.collectAsState()
    val currentFile by viewModel.currentFile.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp) // Reduced from 16.dp
    ) {
        // Current Playing Card - Smaller and more compact
        currentFile?.let { file ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp), // Reduced from 16.dp
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2A2A2A)
                ),
                elevation = CardDefaults.cardElevation(4.dp) // Reduced from 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(12.dp), // Reduced from 16.dp
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Smaller Album Art
                    Box(
                        modifier = Modifier
                            .size(120.dp) // Reduced from 200.dp
                            .clip(RoundedCornerShape(12.dp)) // Reduced from 16.dp
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF9C27B0),
                                        Color(0xFF673AB7)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp), // Reduced from 80.dp
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp)) // Reduced from 16.dp

                    Text(
                        text = file.title,
                        fontSize = 16.sp, // Reduced from 20.sp
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1, // Reduced from 2
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = file.artist,
                        fontSize = 14.sp, // Reduced from 16.sp
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.height(8.dp)) // Reduced from 16.dp

                    // Progress Bar
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { viewModel.seekTo(it.toLong()) },
                        valueRange = 0f..file.duration.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF9C27B0),
                            activeTrackColor = Color(0xFF9C27B0)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatTime(currentPosition),
                            color = Color.Gray,
                            fontSize = 10.sp // Reduced from 12.sp
                        )
                        Text(
                            formatTime(file.duration),
                            color = Color.Gray,
                            fontSize = 10.sp // Reduced from 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp)) // Reduced from 16.dp

                    // Smaller Player Controls
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp), // Reduced from 16.dp
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.playPrevious() },
                            modifier = Modifier
                                .size(40.dp) // Reduced from 56.dp
                                .clip(CircleShape)
                                .background(Color(0xFF333333))
                        ) {
                            Icon(
                                Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp) // Reduced from 32.dp
                            )
                        }

                        IconButton(
                            onClick = {
                                when (playerState) {
                                    PlayerState.PLAYING -> viewModel.pausePlayback()
                                    PlayerState.PAUSED -> viewModel.resumePlayback()
                                    else -> viewModel.playFile(file)
                                }
                            },
                            modifier = Modifier
                                .size(56.dp) // Reduced from 72.dp
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFF9C27B0),
                                            Color(0xFF673AB7)
                                        )
                                    )
                                )
                        ) {
                            Icon(
                                when (playerState) {
                                    PlayerState.PLAYING -> Icons.Default.Pause
                                    else -> Icons.Default.PlayArrow
                                },
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp) // Reduced from 40.dp
                            )
                        }

                        IconButton(
                            onClick = { viewModel.playNext() },
                            modifier = Modifier
                                .size(40.dp) // Reduced from 56.dp
                                .clip(CircleShape)
                                .background(Color(0xFF333333))
                        ) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp) // Reduced from 32.dp
                            )
                        }
                    }
                }
            }
        }

        // Audio Files List
        if (audioFiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp), // Reduced from 64.dp
                        tint = Color.Gray
                    )
                    Text(
                        "No audio files found",
                        color = Color.Gray,
                        fontSize = 14.sp, // Reduced from 16.sp
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Grant storage permission to see your music",
                        color = Color.Gray,
                        fontSize = 12.sp, // Reduced from 14.sp
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp) // Reduced spacing
            ) {
                items(audioFiles) { file ->
                    AudioFileItem(
                        file = file,
                        isPlaying = currentFile?.id == file.id && playerState == PlayerState.PLAYING,
                        onClick = { viewModel.playFile(file) }
                    )
                }
            }
        }
    }
}

@Composable
fun RecorderScreen(viewModel: AudioViewModel) {
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val recordings by viewModel.recordings.collectAsState()
    var selectedFormat by remember { mutableStateOf<AudioFormat>(AudioFormat.MP3) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp) // Reduced from 16.dp
    ) {
        // Recording Controls - More compact
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF2A2A2A)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp), // Reduced from 24.dp
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Smaller Recording Visualization
                Box(
                    modifier = Modifier
                        .size(120.dp) // Reduced from 200.dp
                        .clip(CircleShape)
                        .background(
                            if (isRecording) {
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color.Red.copy(alpha = 0.3f),
                                        Color.Red.copy(alpha = 0.1f)
                                    )
                                )
                            } else {
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF333333),
                                        Color(0xFF1A1A1A)
                                    )
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(16.dp) // Reduced from 24.dp
                                .clip(CircleShape)
                                .background(Color.Red)
                        )
                    } else {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp), // Reduced from 80.dp
                            tint = Color.Gray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp)) // Reduced from 16.dp

                Text(
                    if (isRecording) "Recording..." else "Ready to Record",
                    fontSize = 16.sp, // Reduced from 20.sp
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (isRecording) {
                    Text(
                        formatTime(recordingDuration),
                        fontSize = 20.sp, // Reduced from 24.sp
                        fontWeight = FontWeight.Bold,
                        color = Color.Red
                    )
                }

                Spacer(modifier = Modifier.height(12.dp)) // Reduced from 24.dp

                // Compact Format Selection
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp) // Reduced spacing
                ) {
                    items(AudioFormat.entries.toList()) { format ->
                        FilterChip(
                            onClick = { selectedFormat = format },
                            label = {
                                Text(
                                    format.displayName,
                                    fontSize = 12.sp // Smaller text
                                )
                            },
                            selected = selectedFormat == format,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF9C27B0),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp)) // Reduced from 24.dp

                // Smaller Record Button
                FloatingActionButton(
                    onClick = {
                        if (isRecording) {
                            viewModel.stopRecording()
                        } else {
                            val audioVaultDir = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                                "AudioVault"
                            )
                            if (!audioVaultDir.exists()) {
                                audioVaultDir.mkdirs()
                            }
                            val filename = "recording_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.${selectedFormat.extension}"
                            val outputFile = File(audioVaultDir, filename)
                            viewModel.startRecording(outputFile, selectedFormat)
                        }
                    },
                    modifier = Modifier.size(56.dp), // Reduced from 72.dp
                    containerColor = if (isRecording) Color.Red else Color(0xFF9C27B0)
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                        modifier = Modifier.size(24.dp), // Reduced from 32.dp
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp)) // Reduced from 16.dp

        // Previous Recordings
        Text(
            "Previous Recordings",
            fontSize = 16.sp, // Reduced from 18.sp
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 4.dp) // Reduced from 8.dp
        )

        if (recordings.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No recordings yet",
                    color = Color.Gray,
                    fontSize = 14.sp // Reduced from 16.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp) // Reduced spacing
            ) {
                items(recordings) { recording ->
                    RecordingItem(recording = recording, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun EffectsScreen(viewModel: AudioViewModel) {
    val equalizerBands by viewModel.equalizerBands.collectAsState()
    val noiseReduction by viewModel.noiseReduction.collectAsState()
    val compression by viewModel.compression.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Equalizer
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2A2A2A)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "10-Band Equalizer",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val frequencies = arrayOf("31", "63", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")

                        frequencies.forEachIndexed { index, freq ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "${equalizerBands[index].toInt()}dB",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )

                                Slider(
                                    value = equalizerBands[index],
                                    onValueChange = { viewModel.updateEqualizerBand(index, it) },
                                    valueRange = -12f..12f,
                                    modifier = Modifier
                                        .height(120.dp)
                                        .width(24.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFF9C27B0),
                                        activeTrackColor = Color(0xFF9C27B0)
                                    )
                                )

                                Text(
                                    freq,
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            // Audio Processing
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2A2A2A)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Audio Processing",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Noise Reduction
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Noise Reduction",
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Text(
                                "Spectral subtraction algorithm",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }

                        Switch(
                            checked = noiseReduction,
                            onCheckedChange = { viewModel.toggleNoiseReduction() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF9C27B0)
                            )
                        )
                    }

                    // Dynamic Range Compression
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Dynamic Range Compression",
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Text(
                                "Multi-band compressor for professional sound",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }

                        Switch(
                            checked = compression,
                            onCheckedChange = { viewModel.toggleCompression() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF9C27B0)
                            )
                        )
                    }
                }
            }
        }

        item {
            // Presets
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2A2A2A)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Audio Presets",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    val presets = listOf(
                        "Studio Recording",
                        "Podcast",
                        "Music Playback",
                        "Voice Enhancement",
                        "Bass Boost",
                        "Treble Enhance"
                    )

                    presets.chunked(2).forEach { rowPresets ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowPresets.forEach { preset ->
                                OutlinedButton(
                                    onClick = { /* Apply preset */ },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color.White
                                    ),
                                    border = BorderStroke(1.dp, Color(0xFF9C27B0))
                                ) {
                                    Text(
                                        preset,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioFileItem(
    file: AudioFile,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp) // Reduced from 4.dp
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) Color(0xFF9C27B0).copy(alpha = 0.2f) else Color(0xFF2A2A2A)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp), // Reduced from 16.dp
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Smaller file icon
            Box(
                modifier = Modifier
                    .size(32.dp) // Reduced from 48.dp
                    .clip(CircleShape)
                    .background(
                        if (isPlaying) Color(0xFF9C27B0) else Color(0xFF444444)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Default.VolumeUp else Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp) // Reduced from 24.dp
                )
            }

            Spacer(modifier = Modifier.width(8.dp)) // Reduced from 16.dp

            // File info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = file.title,
                    fontSize = 14.sp, // Reduced from 16.sp
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = file.artist,
                    fontSize = 12.sp, // Reduced from 14.sp
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = file.format.uppercase(),
                        fontSize = 10.sp, // Reduced from 12.sp
                        color = Color(0xFF9C27B0),
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = " • ${formatTime(file.duration)}",
                        fontSize = 10.sp, // Reduced from 12.sp
                        color = Color.Gray
                    )
                }
            }

            // More options with smaller button
            IconButton(
                onClick = { /* Show options menu */ },
                modifier = Modifier.size(24.dp) // Reduced button size
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp) // Reduced icon size
                )
            }
        }
    }
}

@Composable
fun RecordingItem(
    recording: RecordingSession,
    viewModel: AudioViewModel = viewModel()
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp) // Reduced from 4.dp
            .clickable {
                val audioFile = AudioFile(
                    id = recording.id,
                    title = recording.filename,
                    artist = "Recording",
                    album = "My Recordings",
                    duration = recording.duration,
                    path = recording.path,
                    format = recording.format.extension,
                    size = 0L
                )
                viewModel.playFile(audioFile)
            },
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A2A2A)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp), // Reduced from 16.dp
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Smaller recording icon
            Box(
                modifier = Modifier
                    .size(32.dp) // Reduced from 48.dp
                    .clip(CircleShape)
                    .background(Color(0xFF444444)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(16.dp) // Reduced from 24.dp
                )
            }

            Spacer(modifier = Modifier.width(8.dp)) // Reduced from 16.dp

            // Recording info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = recording.filename,
                    fontSize = 14.sp, // Reduced from 16.sp
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
                        .format(Date(recording.timestamp)),
                    fontSize = 12.sp, // Reduced from 14.sp
                    color = Color.Gray,
                    maxLines = 1
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = recording.format.displayName,
                        fontSize = 10.sp, // Reduced from 12.sp
                        color = Color(0xFF9C27B0),
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = " • ${formatTime(recording.duration)}",
                        fontSize = 10.sp, // Reduced from 12.sp
                        color = Color.Gray
                    )
                }
            }

            // Smaller buttons
            IconButton(
                onClick = {
                    val audioFile = AudioFile(
                        id = recording.id,
                        title = recording.filename,
                        artist = "Recording",
                        album = "My Recordings",
                        duration = recording.duration,
                        path = recording.path,
                        format = recording.format.extension,
                        size = 0L
                    )
                    viewModel.playFile(audioFile)
                },
                modifier = Modifier.size(24.dp) // Reduced button size
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play recording",
                    tint = Color(0xFF9C27B0),
                    modifier = Modifier.size(16.dp) // Reduced icon size
                )
            }

            IconButton(
                onClick = { /* Show options menu */ },
                modifier = Modifier.size(24.dp) // Reduced button size
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp) // Reduced icon size
                )
            }
        }
    }
}

// Utility Functions
fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%d:%02d", minutes, remainingSeconds)
}

// File Management Helper
class AudioFileManager {
    companion object {
        fun scanAudioFiles(context: android.content.Context): List<AudioFile> {
            val audioFiles = mutableListOf<AudioFile>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.SIZE
            )

            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Audio.Media.TITLE + " ASC"
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn) ?: "Unknown"
                    val artist = it.getString(artistColumn) ?: "Unknown Artist"
                    val album = it.getString(albumColumn) ?: "Unknown Album"
                    val duration = it.getLong(durationColumn)
                    val path = it.getString(dataColumn)
                    val size = it.getLong(sizeColumn)

                    val format = path.substringAfterLast('.', "")

                    audioFiles.add(
                        AudioFile(
                            id = id.toString(),
                            title = title,
                            artist = artist,
                            album = album,
                            duration = duration,
                            path = path,
                            format = format,
                            size = size
                        )
                    )
                }
            }

            return audioFiles
        }
    }
}

// Advanced Audio Processing (Real implementation would use native C++ code)
class AdvancedAudioProcessor {
    // FFT-based noise reduction
    fun spectralNoiseReduction(audioData: FloatArray, sampleRate: Int): FloatArray {
        val fftSize = 1024
        val hopSize = fftSize / 4
        val processed = audioData.copyOf()

        // This is a simplified implementation
        // Real implementation would use proper FFT libraries
        for (i in 0 until processed.size - fftSize step hopSize) {
            val frame = processed.sliceArray(i until i + fftSize)
            val processedFrame = processFrame(frame)
            System.arraycopy(processedFrame, 0, processed, i, processedFrame.size)
        }

        return processed
    }

    private fun processFrame(frame: FloatArray): FloatArray {
        // Apply window function
        val windowed = applyHannWindow(frame)

        // In real implementation, this would be:
        // 1. FFT
        // 2. Spectral subtraction
        // 3. Inverse FFT
        // 4. Overlap-add

        return windowed
    }

    private fun applyHannWindow(frame: FloatArray): FloatArray {
        val windowed = FloatArray(frame.size)
        for (i in frame.indices) {
            val window = 0.5 * (1.0 - cos(2.0 * PI * i / (frame.size - 1)))
            windowed[i] = (frame[i] * window).toFloat()
        }
        return windowed
    }

    // Multi-band compressor
    fun multiBandCompress(audioData: FloatArray, sampleRate: Int): FloatArray {
        val lowCutoff = 200.0
        val highCutoff = 2000.0

        // Split into 3 bands: low, mid, high
        val lowBand = filterBand(audioData, sampleRate, 0.0, lowCutoff)
        val midBand = filterBand(audioData, sampleRate, lowCutoff, highCutoff)
        val highBand = filterBand(audioData, sampleRate, highCutoff, sampleRate / 2.0)

        // Apply different compression to each band
        val compressedLow = compress(lowBand, 0.6f, 3.0f)
        val compressedMid = compress(midBand, 0.7f, 4.0f)
        val compressedHigh = compress(highBand, 0.8f, 2.0f)

        // Combine bands
        val result = FloatArray(audioData.size)
        for (i in audioData.indices) {
            result[i] = compressedLow[i] + compressedMid[i] + compressedHigh[i]
        }

        return result
    }

    private fun filterBand(audioData: FloatArray, sampleRate: Int, lowFreq: Double, highFreq: Double): FloatArray {
        // Simplified band-pass filter
        // Real implementation would use proper digital filters
        return audioData.copyOf()
    }

    private fun compress(audioData: FloatArray, threshold: Float, ratio: Float): FloatArray {
        val compressed = FloatArray(audioData.size)
        var envelope = 0.0f
        val attackTime = 0.003f // 3ms
        val releaseTime = 0.1f // 100ms

        for (i in audioData.indices) {
            val input = abs(audioData[i])

            // Envelope follower
            envelope = if (input > envelope) {
                envelope + (input - envelope) * attackTime
            } else {
                envelope + (input - envelope) * releaseTime
            }

            // Apply compression
            val gain = if (envelope > threshold) {
                threshold + (envelope - threshold) / ratio
            } else {
                envelope
            } / envelope.coerceAtLeast(0.001f)

            compressed[i] = audioData[i] * gain
        }

        return compressed
    }
}

// MainActivity class
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AudioVaultApp()
        }
    }

    // Add this method to scan and load audio files
    private fun loadAudioFiles(viewModel: AudioViewModel) {
        lifecycleScope.launch {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val audioFiles = AudioFileManager.scanAudioFiles(this@MainActivity)
                viewModel.loadAudioFiles(audioFiles)
            }
        }
    }
}