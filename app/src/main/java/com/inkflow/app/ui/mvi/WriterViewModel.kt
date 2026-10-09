package com.inkflow.app.ui.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkflow.app.ai.EngineRouter
import com.inkflow.app.data.NovelRepository
import com.inkflow.app.data.SettingsStore
import com.inkflow.core.agent.AgentPipeline
import com.inkflow.core.corpus.Blueprint
import com.inkflow.core.corpus.DepthProfile
import com.inkflow.core.corpus.VoiceLibrary
import com.inkflow.core.domain.Chapter
import com.inkflow.core.domain.ChapterStatus
import com.inkflow.core.domain.Character
import com.inkflow.core.domain.Foreshadow
import com.inkflow.core.domain.ForeshadowStatus
import com.inkflow.core.domain.WorldSetting
import com.inkflow.core.quality.QualityEvaluator
import com.inkflow.core.rag.NovelContextStore
import com.inkflow.core.style.StyleAnalyzer
import com.inkflow.core.style.StyleDna
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * 写作台 ViewModel —— MVI 的唯一状态所有者。
 *
 * 所有 Intent 在此收敛为新的 [WriterUiState]；界面层只负责渲染 State 与派发 Intent。
 * 副作用通过一次性 [WriterEffect] 通道下发，避免重组导致重复执行。
 */
class WriterViewModel(
    private val repo: NovelRepository,
    private val settings: SettingsStore,
    private val router: EngineRouter,
    private val contextStore: NovelContextStore,
) : ViewModel() {

    private val _state = MutableStateFlow(WriterUiState())
    val state: StateFlow<WriterUiState> = _state.asStateFlow()

    private val _effects = Channel<WriterEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var generationJob: Job? = null
    private var autoSaveJob: Job? = null
    private var indexJob: Job? = null

    private var pipeline: AgentPipeline? = null
    private var characters: List<Character> = emptyList()
    private var settingsList: List<WorldSetting> = emptyList()
    private var foreshadows: List<Foreshadow> = emptyList()
    private var styleDna: StyleDna? = null

    fun onIntent(intent: WriterIntent) {
        when (intent) {
            is WriterIntent.Load -> load(intent.projectId, intent.chapterId)
            is WriterIntent.EditContent -> editContent(intent.newText)
            is WriterIntent.EditOutline -> {
                _state.value = _state.value.copy(outline = intent.outline, dirty = true)
                scheduleAutoSave()
            }
            is WriterIntent.EditHandoff -> {
                _state.value = _state.value.copy(handoffNote = intent.note, dirty = true)
                scheduleAutoSave()
            }
            is WriterIntent.RenameChapter -> rename(intent.title)
            WriterIntent.Save -> saveNow("手动保存")
            WriterIntent.ToggleChapterStatus -> toggleStatus()
            WriterIntent.GenerateChapter -> generateChapter()
            is WriterIntent.Continue -> continueWriting(intent.instruction, intent.length)
            WriterIntent.Polish -> polish()
            WriterIntent.Proofread -> proofread()
            WriterIntent.Review -> review()
            WriterIntent.CheckConsistency -> checkConsistency()
            WriterIntent.AcceptStreaming -> acceptStreaming()
            WriterIntent.DiscardStreaming -> discardStreaming()
            WriterIntent.CancelGeneration -> cancelGeneration()
            WriterIntent.RunQualityCheck -> runQualityCheck()
            WriterIntent.GenerateHandoff -> generateHandoff()
            WriterIntent.RebuildStyleDna -> rebuildStyleDna()
            is WriterIntent.GenerateOutline -> generateOutline(intent.volumes, intent.chaptersPerVolume)
            is WriterIntent.GenerateBlueprint -> generateBlueprint(intent.answers)
            WriterIntent.ApplyBlueprint -> applyBlueprint()
            WriterIntent.DiscardBlueprint -> _state.value = _state.value.copy(blueprint = null)
            WriterIntent.GenerateStoryBible -> generateStoryBible()
            is WriterIntent.AddChapter -> addChapter(intent.title)
            WriterIntent.SelectChapter -> { /* 由导航层处理 */ }
            WriterIntent.DismissMessage -> _state.value = _state.value.copy(message = null)
            WriterIntent.DismissError -> _state.value = _state.value.copy(error = null)
        }
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 应用层引用，用于取新建作品时暂存的问答答案。
     * 由工厂注入；为空时（例如单元测试）跳过自动蓝图。
     */
    private var appRef: com.inkflow.app.InkFlowApp? = null

    fun attachApp(app: com.inkflow.app.InkFlowApp) {
        appRef = app
    }

    private fun load(projectId: String, chapterId: String?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, projectId = projectId)

            val project = repo.getProject(projectId)
            val projects = project
            _state.value = _state.value.copy(
                projectTitle = projects?.title.orEmpty(),
                genre = projects?.genre.orEmpty(),
            )

            // 恢复文风 DNA
            project?.let { p ->
                repo.getProjectEntity(projectId)?.styleDnaJson?.let { cached ->
                    styleDna = runCatching { json.decodeFromString<StyleDna>(cached) }.getOrNull()
                }
            }

            // 监听章节列表（元信息，不含正文，避免长章节拖慢）
            launch {
                repo.observeChapterMeta(projectId).collectLatest { list ->
                    val current = _state.value.currentChapter
                    val refreshed = current?.let { c -> list.firstOrNull { it.id == c.id } }
                    _state.value = _state.value.copy(
                        chapters = list,
                        currentChapter = refreshed ?: current,
                    )
                }
            }

            // 监听伏笔台账
            launch {
                repo.observeForeshadows(projectId).collectLatest {
                    foreshadows = it
                    _state.value = _state.value.copy(foreshadows = it)
                }
            }

            // 监听角色与设定，供检索使用
            launch { repo.observeCharacters(projectId).collectLatest { characters = it } }
            launch { repo.observeSettings(projectId).collectLatest { settingsList = it } }

            // 打开指定章节，或第一篇
            val targetId = chapterId
                ?: repo.listChapters(projectId).firstOrNull()?.id
            if (targetId != null) {
                openChapter(targetId)
            } else {
                _state.value = _state.value.copy(loading = false)
            }

            // 深度档与声纹：设置变更后立刻生效，不需要重启
            launch {
                settings.writingPrefs.collectLatest { prefs ->
                    _state.value = _state.value.copy(
                        depth = DepthProfile(
                            level = prefs.depthLevel,
                            analogies = prefs.depthAnalogies,
                            ambiguity = prefs.depthAmbiguity,
                        ),
                        voiceProfileName = prefs.voiceProfileName,
                    )
                }
            }

            // 后台重建检索索引（不阻塞界面）
            reindex(projectId)

            refreshEngineStatus()

            // 新建作品首次进入：若带着问答答案，自动开始生成蓝图。
            // 放在这里而不是创建流程里，是因为生成要跑几分钟、要显示进度，
            // 而在写作台里展示进度比让用户干等在建作品页体验好得多。
            appRef?.consumeIntakeAnswers(projectId)?.takeIf { it.isNotEmpty() }?.let { answers ->
                generateBlueprint(answers)
            }
        }
    }

    private suspend fun openChapter(chapterId: String) {
        val chapter = repo.getChapter(chapterId)
        _state.value = _state.value.copy(
            currentChapter = chapter,
            contentBlocks = TextBlock.of(chapter?.content.orEmpty()),
            wordCount = chapter?.wordCount ?: 0,
            outline = chapter?.outline.orEmpty(),
            handoffNote = chapter?.handoffNote.orEmpty(),
            dirty = false,
            streamingText = "",
            qualityReport = null,
            consistencyFindings = emptyList(),
            reviewVerdict = null,
            loading = false,
            styleDna = styleDna,
        )
    }

    private suspend fun refreshEngineStatus() {
        val engine = router.activeEngine()
        _state.value = _state.value.copy(
            activeEngineName = engine.displayName,
            activeEngineId = engine.id,
        )
    }

    /** 重建端侧 RAG 索引。放后台，失败不影响写作。 */
    private fun reindex(projectId: String) {
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            runCatching {
                val chapters = repo.listChapters(projectId)
                contextStore.rebuild(projectId, chapters, characters, settingsList, foreshadows)
            }.onFailure {
                // 索引失败只影响检索质量，不阻断写作
            }
        }
    }

    /** 单章增量索引：写完一章立刻可被后续章节检索到。 */
    private fun reindexChapter(chapter: Chapter) {
        viewModelScope.launch {
            runCatching {
                contextStore.indexChapter(chapter.projectId, chapter)
                repo.listForeshadows(chapter.projectId).forEach {
                    contextStore.indexForeshadow(chapter.projectId, it)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 编辑
    // ------------------------------------------------------------------

    private fun editContent(newText: String) {
        val current = _state.value.currentChapter ?: return
        val updated = current.copy(content = newText, wordCount = Chapter.countWords(newText))
        _state.value = _state.value.copy(
            currentChapter = updated,
            contentBlocks = TextBlock.of(newText),
            wordCount = updated.wordCount,
            dirty = true,
        )
        scheduleAutoSave()
    }

    private fun rename(title: String) {
        val c = _state.value.currentChapter ?: return
        _state.value = _state.value.copy(
            currentChapter = c.copy(title = title),
            chapters = _state.value.chapters.map { if (it.id == c.id) c.copy(title = title) else it },
            dirty = true,
        )
        scheduleAutoSave()
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            val interval = settings.writingPrefs.first().autoSaveIntervalMs
            kotlinx.coroutines.delay(interval)
            persist(silent = true)
        }
    }

    private fun saveNow(reason: String) {
        viewModelScope.launch {
            persist(silent = false)
            _effects.send(WriterEffect.ShowToast("已保存 · $reason"))
        }
    }

    private suspend fun persist(silent: Boolean) {
        val s = _state.value
        val c = s.currentChapter ?: return
        if (!s.dirty && silent) return
        repo.saveChapter(c.copy(outline = s.outline, handoffNote = s.handoffNote))
        _state.value = _state.value.copy(dirty = false, lastSavedAt = System.currentTimeMillis())
    }

    private fun toggleStatus() {
        viewModelScope.launch {
            val c = _state.value.currentChapter ?: return@launch
            val prefs = settings.writingPrefs.first()

            if (c.status == ChapterStatus.Polished) {
                // 从「已润色」升到「定稿」需要过质量门禁
                val report = _state.value.qualityReport
                    ?: QualityEvaluator.evaluate(
                        c, characters, foreshadows, styleDna, genre = _state.value.genre,
                    )
                if (!report.passesGate(prefs.qualityGateScore)) {
                    _state.value = _state.value.copy(error = "质量门禁未通过：${report.gateReason(prefs.qualityGateScore)}")
                    return@launch
                }
            }
            val next = when (c.status) {
                ChapterStatus.Draft -> ChapterStatus.Polished
                ChapterStatus.Polished -> ChapterStatus.Locked
                ChapterStatus.Locked -> ChapterStatus.Draft
            }
            repo.setChapterStatus(c.id, next)
            _state.value = _state.value.copy(
                currentChapter = c.copy(status = next),
                message = "章节状态：${next.label}",
            )
        }
    }

    private fun addChapter(title: String) {
        viewModelScope.launch {
            val s = _state.value
            val chapter = repo.createChapter(s.projectId, title)
            _state.value = _state.value.copy(chapters = s.chapters + chapter)
            openChapter(chapter.id)
            _effects.send(WriterEffect.ShowToast("已新建《${chapter.title}》"))
        }
    }

    // ------------------------------------------------------------------
    // AI 生成
    // ------------------------------------------------------------------

    private suspend fun ensurePipeline(): AgentPipeline {
        pipeline?.let { return it }
        val engine = router.activeEngine()
        val p = AgentPipeline(engine)
        pipeline = p
        _state.value = _state.value.copy(
            activeEngineName = engine.displayName,
            activeEngineId = engine.id,
        )
        return p
    }

    /** 组装写作上下文：检索设定 + 角色 + 伏笔 + 最近正文尾部。 */
    private suspend fun buildContext(chapter: Chapter, query: String): String {
        val projectId = chapter.projectId
        val prev = repo.previousChapters(projectId, chapter.order, 2)
        val ctx = contextStore.buildWritingContext(
            projectId = projectId,
            query = query,
            recentChapterTexts = prev.map { it.content },
            maxChars = 3600,
        )
        val globalRecap = repo.getProjectEntity(projectId)?.globalRecap.orEmpty()
        return buildString {
            if (globalRecap.isNotBlank()) {
                appendLine("【全书前情提要】")
                appendLine(globalRecap)
                appendLine()
            }
            append(ctx.promptBlock)
        }.trim()
    }

    private fun generateChapter() {
        val chapter = _state.value.currentChapter ?: return
        if (_state.value.isGenerating) return

        generationJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isGenerating = true, streamingText = "", error = null,
                pendingKind = PendingKind.Append,
                generationLabel = "正在生成第${chapter.order}章…",
            )
            try {
                val p = ensurePipeline()
                val prefs = settings.writingPrefs.first()
                val query = listOf(chapter.title, chapter.outline, chapter.content.takeLast(300))
                    .filter { it.isNotBlank() }.joinToString(" ")
                val context = buildContext(chapter, query)
                val styleBlock = styleDna?.toPromptBlock().orEmpty()

                _state.value = _state.value.copy(lastContextPreview = context)

                // 先用规划 Agent 细化本章细纲（若用户没写细纲）
                var outline = chapter.outline
                if (outline.isBlank()) {
                    val planned = p.planChapter(
                        genre = _state.value.genre,
                        projectTitle = _state.value.projectTitle,
                        chapterOrder = chapter.order,
                        chapterTitle = chapter.title,
                        contextBlock = context,
                        previousHandoff = repo.previousChapters(chapter.projectId, chapter.order, 1)
                            .firstOrNull()?.handoffNote.orEmpty(),
                    )
                    outline = planned.output
                    _state.value = _state.value.copy(outline = outline)
                }

                val prevHandoff = repo.previousChapters(chapter.projectId, chapter.order, 1)
                    .firstOrNull()?.handoffNote.orEmpty()

                val result = p.writeChapter(
                    projectTitle = _state.value.projectTitle,
                    chapterOrder = chapter.order,
                    chapterTitle = chapter.title,
                    outline = outline,
                    styleBlock = styleBlock,
                    contextBlock = context,
                    previousHandoff = prevHandoff,
                    targetWords = prefs.chapterTargetWords,
                    genre = _state.value.genre,
                    depth = _state.value.depth,
                    voiceName = _state.value.voiceProfileName.ifBlank { null },
                )

                _state.value = _state.value.copy(
                    streamingText = result.output,
                    isGenerating = false,
                    degraded = result.degraded,
                    generationLabel = "",
                )
                _effects.send(WriterEffect.ShowToast("生成完成，确认后可插入正文"))
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isGenerating = false,
                    generationLabel = "",
                    error = "生成失败：${t.message}",
                )
            }
        }
    }

    private fun continueWriting(instruction: String, length: Int) {
        val chapter = _state.value.currentChapter ?: return
        if (_state.value.isGenerating) return

        generationJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isGenerating = true, streamingText = "", error = null,
                pendingKind = PendingKind.Append,
                generationLabel = "正在续写…",
            )
            try {
                val p = ensurePipeline()
                val tail = chapter.content.takeLast(2000)
                val context = buildContext(chapter, tail.takeLast(400))
                val result = p.continueWriting(
                    existingTail = tail,
                    instruction = instruction,
                    length = length,
                    styleBlock = styleDna?.toPromptBlock().orEmpty(),
                    contextBlock = context,
                    genre = _state.value.genre,
                    depth = _state.value.depth,
                    voiceName = _state.value.voiceProfileName.ifBlank { null },
                )
                _state.value = _state.value.copy(
                    streamingText = result.output,
                    isGenerating = false,
                    degraded = result.degraded,
                    generationLabel = "",
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "",
                    error = "续写失败：${t.message}",
                )
            }
        }
    }

    private fun polish() {
        val chapter = _state.value.currentChapter ?: return
        if (chapter.content.isBlank()) {
            _state.value = _state.value.copy(error = "正文为空，无法润色")
            return
        }
        if (_state.value.isGenerating) return

        generationJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isGenerating = true, streamingText = "", error = null,
                pendingKind = PendingKind.Replace,
                generationLabel = "正在润色…",
            )
            try {
                val p = ensurePipeline()
                val result = p.polish(
                    chapter.content,
                    styleDna?.toPromptBlock().orEmpty(),
                    genre = _state.value.genre,
                    depth = _state.value.depth,
                )
                _state.value = _state.value.copy(
                    streamingText = result.output, isGenerating = false,
                    degraded = result.degraded, generationLabel = "",
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "", error = "润色失败：${t.message}",
                )
            }
        }
    }

    private fun proofread() {
        val chapter = _state.value.currentChapter ?: return
        if (chapter.content.isBlank()) {
            _state.value = _state.value.copy(error = "正文为空，无法校对")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isGenerating = true, generationLabel = "正在校对…", error = null)
            try {
                val p = ensurePipeline()
                val result = p.proofread(chapter.content)
                val fixCount = result.output.size
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "",
                    message = if (fixCount == 0) "未发现错别字或语法问题" else "发现 $fixCount 处可修改点，见质量面板",
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "", error = "校对失败：${t.message}",
                )
            }
        }
    }

    private fun review() {
        val chapter = _state.value.currentChapter ?: return
        if (chapter.content.isBlank()) {
            _state.value = _state.value.copy(error = "正文为空，无法审阅")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isGenerating = true, generationLabel = "主编正在审稿…", error = null)
            try {
                val p = ensurePipeline()
                val result = p.review(chapter.title, chapter.content)
                _state.value = _state.value.copy(
                    reviewVerdict = result.output, isGenerating = false, generationLabel = "",
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "", error = "审阅失败：${t.message}",
                )
            }
        }
    }

    private fun checkConsistency() {
        val chapter = _state.value.currentChapter ?: return
        if (chapter.content.isBlank()) {
            _state.value = _state.value.copy(error = "正文为空，无法检查")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isGenerating = true, generationLabel = "正在核对设定…", error = null)
            try {
                val p = ensurePipeline()
                val context = buildContext(chapter, chapter.content.take(1500))
                val result = p.checkConsistency(context, chapter.content)
                _state.value = _state.value.copy(
                    consistencyFindings = result.output, isGenerating = false, generationLabel = "",
                    message = if (result.output.isEmpty()) "未发现与设定冲突之处" else "发现 ${result.output.size} 处疑似冲突",
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "", error = "一致性检查失败：${t.message}",
                )
            }
        }
    }

    /**
     * 把 AI 产出的新段落追加到已有正文之后。
     *
     * 注意这里**必须显式加括号**：曾写成
     *   `content + if (cond) "" else "\n\n" + text`
     * Kotlin 会解析为 `content + (if (cond) "" else ("\n\n" + text))`，
     * 于是当正文为空或以换行结尾时（正是首次生成最常见的情形），
     * AI 文本会被整体丢弃 —— 表现为点「插入正文」毫无反应。
     */
    private fun appendBlock(existing: String, addition: String): String {
        if (addition.isBlank()) return existing
        if (existing.isBlank()) return addition
        val separator = when {
            existing.endsWith("\n\n") -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        return existing + separator + addition
    }

    private fun acceptStreaming() {
        val s = _state.value
        val chapter = s.currentChapter ?: return
        if (s.streamingText.isBlank()) return

        val merged = when {
            // 润色 / 一致性重写结果：整篇替换
            s.pendingKind == PendingKind.Replace -> s.streamingText
            // 续写 / 整章生成：追加到正文末尾
            else -> appendBlock(chapter.content, s.streamingText)
        }
        val updated = chapter.copy(content = merged, wordCount = Chapter.countWords(merged))
        _state.value = s.copy(
            currentChapter = updated,
            contentBlocks = TextBlock.of(merged),
            wordCount = updated.wordCount,
            streamingText = "",
            dirty = true,
        )
        viewModelScope.launch {
            repo.saveChapterContent(updated.id, merged, reason = "AI 生成")
            reindexChapter(updated)
            _state.value = _state.value.copy(dirty = false)
            runQualityCheck()
        }
    }

    private fun discardStreaming() {
        _state.value = _state.value.copy(streamingText = "", generationLabel = "")
    }

    private fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        _state.value = _state.value.copy(isGenerating = false, generationLabel = "", streamingText = "")
    }

    // ------------------------------------------------------------------
    // 质量与文风
    // ------------------------------------------------------------------

    private fun runQualityCheck() {
        viewModelScope.launch {
            val s = _state.value
            val chapter = s.currentChapter ?: return@launch
            if (chapter.content.isBlank()) return@launch
            val prev = repo.previousChapters(chapter.projectId, chapter.order, 2).map { it.content }
            val report = QualityEvaluator.evaluate(
                chapter = chapter,
                characters = characters,
                foreshadows = foreshadows,
                styleDna = styleDna,
                recentTexts = prev,
                genre = _state.value.genre,
            )
            repo.updateChapterScore(chapter.id, report.totalScore)
            _state.value = _state.value.copy(qualityReport = report)
        }
    }

    private fun generateHandoff() {
        val chapter = _state.value.currentChapter ?: return
        if (chapter.content.isBlank()) {
            _state.value = _state.value.copy(error = "正文为空，无法生成交接笔记")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isGenerating = true, generationLabel = "正在生成交接笔记…")
            try {
                val p = ensurePipeline()
                val result = p.makeHandoff(chapter.title, chapter.content)
                _state.value = _state.value.copy(
                    handoffNote = result.output, isGenerating = false, generationLabel = "", dirty = true,
                )
                persist(silent = true)

                // LOOM 全局反馈：定期把交接笔记压缩为全书前情提要
                val all = repo.listChapters(chapter.projectId)
                    .sortedBy { it.order }
                    .map { it.handoffNote }
                    .filter { it.isNotBlank() }
                if (all.size >= 3) {
                    val recap = p.makeGlobalRecap(all.takeLast(15))
                    if (recap.output.isNotBlank()) {
                        repo.saveGlobalRecap(chapter.projectId, recap.output)
                    }
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "", error = "交接笔记生成失败：${t.message}",
                )
            }
        }
    }

    private fun rebuildStyleDna() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isGenerating = true, generationLabel = "正在蒸馏文风 DNA…")
            try {
                val projectId = _state.value.projectId
                val chapters = repo.listChapters(projectId)
                val sample = chapters
                    .filter { it.content.length > 500 }
                    .sortedByDescending { it.wordCount }
                    .take(5)
                    .joinToString("\n\n") { it.content }
                if (sample.length < 500) {
                    _state.value = _state.value.copy(
                        isGenerating = false, generationLabel = "",
                        error = "样本不足：至少需要一章 500 字以上的正文才能蒸馏文风",
                    )
                    return@launch
                }
                val dna = StyleAnalyzer.analyze(sample)
                styleDna = dna
                repo.saveStyleDna(projectId, json.encodeToString(StyleDna.serializer(), dna))
                _state.value = _state.value.copy(styleDna = dna, isGenerating = false, generationLabel = "")
                runQualityCheck()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "", error = "文风蒸馏失败：${t.message}",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 规划
    // ------------------------------------------------------------------

    private fun generateOutline(volumes: Int, chaptersPerVolume: Int) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isGenerating = true, generationLabel = "规划全书中…", error = null)
            try {
                val p = ensurePipeline()
                val project = repo.getProject(_state.value.projectId)
                val result = p.planNovel(
                    projectTitle = _state.value.projectTitle,
                    genre = _state.value.genre,
                    logline = project?.logline.orEmpty(),
                    premise = project?.premise.orEmpty(),
                    targetWords = project?.targetWords ?: 1_000_000L,
                    volumeCount = volumes,
                    chaptersPerVolume = chaptersPerVolume,
                )

                // 写入分卷与章节
                var created = 0
                for ((vi, vol) in result.output.volumes.withIndex()) {
                    val volume = repo.createVolume(
                        projectId = _state.value.projectId,
                        title = vol.title.ifBlank { "第${vi + 1}卷" },
                    )
                    for (ch in vol.chapters) {
                        repo.createChapter(
                            projectId = _state.value.projectId,
                            title = ch.title,
                            volumeId = volume.id,
                            outline = ch.detail,
                        )
                        created++
                    }
                }
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "",
                    message = "已生成 ${result.output.volumes.size} 卷、$created 章大纲",
                )
                reindex(_state.value.projectId)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "", error = "大纲生成失败：${t.message}",
                )
            }
        }
    }

    private fun generateStoryBible() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isGenerating = true, generationLabel = "构建作品蓝图…", error = null)
            try {
                val p = ensurePipeline()
                val project = repo.getProject(_state.value.projectId)
                val result = p.buildStoryBible(
                    genre = _state.value.genre,
                    logline = project?.logline.orEmpty(),
                    premise = project?.premise.orEmpty(),
                )
                val created = parseAndSaveBible(_state.value.projectId, result.output)
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "",
                    message = if (created == 0) "蓝图解析失败，请在设置中重试" else "已生成 $created 个角色与伏笔",
                )
                reindex(_state.value.projectId)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isGenerating = false, generationLabel = "", error = "蓝图生成失败：${t.message}",
                )
            }
        }
    }

    /** 解析蓝图 JSON 并落库。解析失败返回 0，不抛异常。 */
    private fun parseAndSaveBible(projectId: String, raw: String): Int {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        var count = 0
        return try {
            val root = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: return 0
            val chars = root["characters"] as? kotlinx.serialization.json.JsonArray
            chars?.forEach { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val name = o["name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                if (name.isBlank()) return@forEach
                viewModelScope.launch {
                    repo.saveCharacter(
                        Character(
                            id = NovelRepository.newId(),
                            projectId = projectId,
                            name = name,
                            role = o.strOf("role"),
                            personality = o.strOf("personality"),
                            goal = o.strOf("goal"),
                            arc = o.strOf("arc"),
                            relationships = o.strOf("relationships"),
                        )
                    )
                }
                count++
            }
            val fs = root["foreshadows"] as? kotlinx.serialization.json.JsonArray
            fs?.forEach { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val title = o["title"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                if (title.isBlank()) return@forEach
                val planned = o["plannedResolveAt"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 0
                viewModelScope.launch {
                    repo.saveForeshadow(
                        Foreshadow(
                            id = NovelRepository.newId(),
                            projectId = projectId,
                            title = title,
                            detail = o.strOf("detail"),
                            plannedResolveAt = planned,
                            status = ForeshadowStatus.Planted,
                            importance = 2,
                        )
                    )
                }
                count++
            }
            count
        } catch (t: Throwable) {
            0
        }
    }

    // ------------------------------------------------------------------
    // 全书蓝图（需求 2）
    // ------------------------------------------------------------------

    /**
     * 生成全书蓝图。
     *
     * 结果进 [WriterUiState.blueprint]，**不直接入库**——
     * 规划是作品的地基，必须让作者先看到再决定（同 ADR-5 的原则）。
     */
    private fun generateBlueprint(answers: Map<String, String>) {
        val projectId = _state.value.projectId
        if (projectId.isBlank()) return
        if (_state.value.blueprintGenerating) return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                blueprintGenerating = true,
                blueprintStage = "正在准备…",
                blueprintProgress = 0f,
                error = null,
            )
            try {
                val p = ensurePipeline()
                val project = repo.getProject(projectId)
                val entity = repo.getProjectEntity(projectId)

                val result = p.expandBlueprint(
                    projectId = projectId,
                    title = _state.value.projectTitle,
                    genre = _state.value.genre,
                    tone = entity?.tone.orEmpty(),
                    audience = entity?.audience.orEmpty().ifBlank { "通用" },
                    logline = project?.logline.orEmpty(),
                    premise = project?.premise.orEmpty(),
                    targetWords = project?.targetWords ?: 1_000_000L,
                    answers = answers,
                    voiceName = _state.value.voiceProfileName.ifBlank { null },
                    onProgress = { stage, progress ->
                        // 回调来自生成协程，写状态前切到主线程语义
                        viewModelScope.launch {
                            _state.value = _state.value.copy(
                                blueprintStage = stage,
                                blueprintProgress = progress.toFloat(),
                            )
                        }
                    },
                )

                _state.value = _state.value.copy(
                    blueprint = result.output,
                    blueprintGenerating = false,
                    blueprintStage = "",
                    blueprintProgress = 1f,
                    degraded = result.degraded,
                    message = "蓝图已生成：${result.output.volumes.size} 卷 / " +
                        "${result.output.totalChapters} 章，确认后写入",
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    blueprintGenerating = false,
                    blueprintStage = "",
                    error = "蓝图生成失败：${t.message}",
                )
            }
        }
    }

    /**
     * 把蓝图写入数据库。
     *
     * 覆盖策略：分卷与章节**追加**，不删除已有内容。
     * 理由：作者可能已经手写了几章，重建蓝图不该毁掉它们。
     */
    private fun applyBlueprint() {
        val blueprint = _state.value.blueprint ?: return
        val projectId = _state.value.projectId
        if (projectId.isBlank()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(blueprintGenerating = true, blueprintStage = "正在写入…")
            try {
                var volumeCount = 0
                var chapterCount = 0

                blueprint.volumes.forEach { vp ->
                    val volume = repo.createVolume(
                        projectId = projectId,
                        title = vp.title,
                        synopsis = vp.synopsis,
                    )
                    volumeCount++
                    vp.chapters.forEach { cp ->
                        repo.createChapter(
                            projectId = projectId,
                            title = cp.title,
                            volumeId = volume.id,
                            outline = cp.raw.ifBlank { cp.summaryLine },
                        )
                        chapterCount++
                    }
                }

                if (blueprint.settings.isNotEmpty()) {
                    repo.saveSettings(
                        blueprint.settings.map {
                            WorldSetting(
                                id = NovelRepository.newId(),
                                projectId = projectId,
                                category = it.category,
                                name = it.name,
                                content = it.content,
                                tags = it.tags,
                            )
                        }
                    )
                }

                if (blueprint.characters.isNotEmpty()) {
                    repo.saveCharacters(
                        blueprint.characters.map {
                            Character(
                                id = NovelRepository.newId(),
                                projectId = projectId,
                                name = it.name,
                                role = it.role,
                                gender = it.gender,
                                age = it.age,
                                appearance = it.appearance,
                                personality = it.personality,
                                background = it.background,
                                goal = it.goal,
                                arc = it.arc,
                                relationships = it.relationships,
                            )
                        }
                    )
                }

                if (blueprint.foreshadows.isNotEmpty()) {
                    repo.saveForeshadows(
                        blueprint.foreshadows.map {
                            Foreshadow(
                                id = NovelRepository.newId(),
                                projectId = projectId,
                                title = it.title,
                                detail = it.detail,
                                plantedAt = it.plantedAt,
                                plannedResolveAt = it.plannedResolveAt,
                                importance = it.importance,
                                status = ForeshadowStatus.Planted,
                            )
                        }
                    )
                }

                // 把蓝图里解出的深度同步进设置，后续生成立刻按新档位走
                val prefs = settings.writingPrefs.first()
                settings.saveWritingPrefs(
                    prefs.copy(
                        depthLevel = blueprint.depth.level,
                        depthAnalogies = blueprint.depth.analogies,
                        depthAmbiguity = blueprint.depth.ambiguity,
                    )
                )

                _state.value = _state.value.copy(
                    blueprint = null,
                    blueprintGenerating = false,
                    blueprintStage = "",
                    message = "已写入 $volumeCount 卷 $chapterCount 章，" +
                        "${blueprint.settings.size} 条设定 / ${blueprint.characters.size} 位角色 / " +
                        "${blueprint.foreshadows.size} 条伏笔",
                )
                reindex(projectId)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    blueprintGenerating = false,
                    blueprintStage = "",
                    error = "蓝图写入失败：${t.message}",
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        autoSaveJob?.cancel()
        indexJob?.cancel()
        router.releaseAll()
    }

    companion object {
        /** ViewModel 工厂所需的依赖由 [com.inkflow.app.InkFlowApp] 提供 */
        fun factory(app: com.inkflow.app.InkFlowApp): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return WriterViewModel(
                        repo = app.repository,
                        settings = app.settingsStore,
                        router = app.router,
                        contextStore = app.contextStore,
                    ).also { it.attachApp(app) } as T
                }
            }
    }
}

private fun kotlinx.serialization.json.JsonObject.strOf(key: String): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
