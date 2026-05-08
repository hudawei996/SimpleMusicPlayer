package com.goals.simplemusicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var seekBar: SeekBar
    private lateinit var tvSongTitle: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvLyrics: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnPrevious: Button
    private lateinit var btnNext: Button
    private lateinit var btnSelectFolder: Button

    private var isPlaying = false
    private var songList = listOf<SongInfo>()
    private var currentSongIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 1000L

    private val READ_EXTERNAL_STORAGE_REQUEST_CODE = 1
    private val TAG = "MusicPlayer"
    
    private data class SongInfo(
        val audioUri: Uri,
        val songName: String,
        var lyricsUri: Uri? = null,
        var lyricsContent: String? = null
    )

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let {
            Log.d(TAG, "选择的文件夹URI: $it")
            
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            scanFolderForSongs(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupMediaPlayer()
        setupClickListeners()
        checkPermission()
    }

    private fun initViews() {
        seekBar = findViewById(R.id.seekBar)
        tvSongTitle = findViewById(R.id.tvSongTitle)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        tvLyrics = findViewById(R.id.tvLyrics)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnNext = findViewById(R.id.btnNext)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
    }

    private fun setupMediaPlayer() {
        mediaPlayer = MediaPlayer()
        
        mediaPlayer.setOnCompletionListener {
            playNextSong()
        }

        mediaPlayer.setOnPreparedListener {
            tvTotalTime.text = formatTime(mediaPlayer.duration)
            seekBar.max = mediaPlayer.duration
            startUpdatingSeekBar()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer.seekTo(progress)
                    tvCurrentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupClickListeners() {
        btnPlayPause.setOnClickListener {
            if (isPlaying) {
                pauseMusic()
            } else {
                playMusic()
            }
        }

        btnPrevious.setOnClickListener {
            playPreviousSong()
        }

        btnNext.setOnClickListener {
            playNextSong()
        }

        btnSelectFolder.setOnClickListener {
            openFolderPicker()
        }
    }

    private fun playMusic() {
        if (songList.isNotEmpty()) {
            mediaPlayer.start()
            isPlaying = true
            btnPlayPause.text = "暂停"
        }
    }

    private fun pauseMusic() {
        mediaPlayer.pause()
        isPlaying = false
        btnPlayPause.text = "播放"
    }

    private fun playNextSong() {
        if (songList.isNotEmpty()) {
            currentSongIndex = (currentSongIndex + 1) % songList.size
            loadAndPlaySong(songList[currentSongIndex])
        }
    }

    private fun playPreviousSong() {
        if (songList.isNotEmpty()) {
            currentSongIndex = if (currentSongIndex - 1 < 0) songList.size - 1 else currentSongIndex - 1
            loadAndPlaySong(songList[currentSongIndex])
        }
    }

    private fun loadAndPlaySong(songInfo: SongInfo) {
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(this, songInfo.audioUri)
            mediaPlayer.prepareAsync()
            
            tvSongTitle.text = songInfo.songName
            
            if (songInfo.lyricsContent == null && songInfo.lyricsUri != null) {
                loadLyricsForSong(songInfo)
            } else {
                loadLyrics(songInfo.lyricsContent)
            }
            
            isPlaying = false
            btnPlayPause.text = "播放"
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "无法播放该文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLyricsForSong(songInfo: SongInfo) {
        try {
            Log.d(TAG, "开始加载歌词: ${songInfo.songName}")
            Log.d(TAG, "歌词URI: ${songInfo.lyricsUri}")
            
            songInfo.lyricsUri?.let { uri ->
                Log.d(TAG, "尝试打开歌词文件: $uri")
                
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    Log.d(TAG, "成功打开歌词文件输入流")
                    val content = inputStream.bufferedReader().use { it.readText() }
                    songInfo.lyricsContent = content
                    Log.d(TAG, "歌词内容长度: ${content.length}")
                    Log.d(TAG, "歌词前100字符: ${content.take(100)}")
                    loadLyrics(content)
                } ?: run {
                    Log.e(TAG, "无法打开歌词文件输入流")
                }
            } ?: run {
                Log.d(TAG, "歌词URI为null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载歌词失败", e)
            e.printStackTrace()
        }
    }

    private fun loadLyrics(lyricsContent: String?) {
        if (lyricsContent != null && lyricsContent.isNotEmpty()) {
            Log.d(TAG, "歌词加载成功，长度: ${lyricsContent.length}")
            tvLyrics.text = parseLyrics(lyricsContent)
        } else {
            Log.d(TAG, "歌词内容为空")
            tvLyrics.text = "暂无歌词"
        }
    }

    private fun parseLyrics(lyricsContent: String): String {
        val lines = lyricsContent.split("\n")
        val parsedLyrics = StringBuilder()
        
        for (line in lines) {
            if (line.contains("]")) {
                val text = line.substringAfter("]").trim()
                if (text.isNotEmpty()) {
                    parsedLyrics.append(text).append("\n")
                }
            }
        }
        
        return if (parsedLyrics.isEmpty()) "暂无歌词" else parsedLyrics.toString()
    }

    private fun startUpdatingSeekBar() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (mediaPlayer.isPlaying) {
                    seekBar.progress = mediaPlayer.currentPosition
                    tvCurrentTime.text = formatTime(mediaPlayer.currentPosition)
                }
                handler.postDelayed(this, updateInterval)
            }
        }, updateInterval)
    }

    private fun formatTime(milliseconds: Int): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds.toLong())
        val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds.toLong()) - 
                     TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun scanFolderForSongs(treeUri: Uri) {
        try {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            Log.d(TAG, "文件夹文档ID: $documentId")
            
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            Log.d(TAG, "扫描文件夹URI: $childrenUri")
            
            val cursor = contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )
            
            if (cursor == null) {
                Log.e(TAG, "查询文件夹失败，cursor为null")
                Toast.makeText(this, "无法访问该文件夹", Toast.LENGTH_SHORT).show()
                return
            }
            
            val songsMap = mutableMapOf<String, SongInfo>()
            val lyricsMap = mutableMapOf<String, Uri>()
            
            Log.d(TAG, "开始遍历文件...")
            
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0)
                val displayName = cursor.getString(1)
                val mimeType = cursor.getString(2)
                
                Log.d(TAG, "文件: $displayName, MIME: $mimeType, ID: $docId")
                
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                
                val isAudio = mimeType?.startsWith("audio/") == true || 
                             displayName.endsWith(".flac", ignoreCase = true) || 
                             displayName.endsWith(".mp3", ignoreCase = true) || 
                             displayName.endsWith(".mp4", ignoreCase = true) ||
                             displayName.endsWith(".wav", ignoreCase = true)
                
                val isLyrics = displayName.endsWith(".lrc", ignoreCase = true)
                
                if (isAudio) {
                    val songName = displayName.substringBeforeLast(".")
                    songsMap[songName] = SongInfo(fileUri, songName)
                    Log.d(TAG, "✓ 找到音频文件: $songName")
                } else if (isLyrics) {
                    val songName = displayName.substringBeforeLast(".")
                    lyricsMap[songName] = fileUri
                    Log.d(TAG, "✓ 找到歌词文件: $songName")
                }
            }
            
            cursor.close()
            
            Log.d(TAG, "扫描结果 - 音频: ${songsMap.size}, 歌词: ${lyricsMap.size}")
            Log.d(TAG, "音频列表: ${songsMap.keys}")
            Log.d(TAG, "歌词列表: ${lyricsMap.keys}")
            
            val finalSongList = songsMap.map { (songName, song) ->
                var matched = false
                
                lyricsMap.forEach { (lyricsName, lyricsUri) ->
                    if (!matched && isNameMatch(songName, lyricsName)) {
                        song.lyricsUri = lyricsUri
                        matched = true
                        Log.d(TAG, "✓ 匹配成功: '$songName' -> '$lyricsName'")
                    }
                }
                
                if (!matched) {
                    Log.d(TAG, "✗ 未找到歌词: $songName")
                }
                song
            }
            
            songList = finalSongList
            Log.d(TAG, "最终歌曲列表数量: ${songList.size}")
            
            if (songList.isNotEmpty()) {
                currentSongIndex = 0
                loadAndPlaySong(songList[0])
                Toast.makeText(this, "找到 ${songList.size} 首歌曲", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "未找到音频文件", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "扫描文件夹失败", e)
            e.printStackTrace()
            Toast.makeText(this, "扫描文件夹失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun isNameMatch(songName: String, lyricsName: String): Boolean {
        if (songName.equals(lyricsName, ignoreCase = true)) {
            return true
        }
        
        if (songName.contains(lyricsName, ignoreCase = true)) {
            return true
        }
        
        if (lyricsName.contains(songName, ignoreCase = true)) {
            return true
        }
        
        val songParts = songName.split(Regex("[-_\\s]+")).filter { it.isNotEmpty() }
        val lyricsParts = lyricsName.split(Regex("[-_\\s]+")).filter { it.isNotEmpty() }
        
        for (songPart in songParts) {
            for (lyricsPart in lyricsParts) {
                if (songPart.equals(lyricsPart, ignoreCase = true)) {
                    return true
                }
            }
        }
        
        return false
    }

    private fun openFolderPicker() {
        folderPickerLauncher.launch(null)
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                READ_EXTERNAL_STORAGE_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == READ_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要存储权限才能播放音乐", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mediaPlayer.isInitialized) {
            mediaPlayer.release()
        }
        handler.removeCallbacksAndMessages(null)
    }
}