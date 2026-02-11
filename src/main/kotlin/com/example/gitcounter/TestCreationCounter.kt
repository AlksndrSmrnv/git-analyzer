package com.example.gitcounter

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess

private const val DEFAULT_SINCE_DAYS = 7
private const val MAX_ANNOTATION_SCAN_LINES = 20
private const val MAX_EXAMPLES_PER_AUTHOR = 10

private val PACKAGE_REGEX = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
private val CLASS_DECL_REGEX = Regex("""\b(class|object)\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
private val TEST_ANNOTATION_REGEX = Regex("""@(?:org\.junit\.jupiter\.api\.)?Test\b""")
private val DISPLAY_NAME_REGEX =
    Regex("""@(?:org\.junit\.jupiter\.api\.)?DisplayName\s*\((.*?)\)""", setOf(RegexOption.DOT_MATCHES_ALL))
private val STRING_LITERAL_REGEX =
    Regex("\"((?:\\\\.|[^\"\\\\])*)\"", setOf(RegexOption.DOT_MATCHES_ALL))
private val FUN_DECL_REGEX =
    Regex("""\bfun\s+(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)\s*\(""")

private enum class Mode {
    ALL,
    SINCE_DAYS
}

private data class CliOptions(
    val mode: Mode,
    val sinceDays: Int?,
    val branch: String?,
    val verbose: Boolean,
    val help: Boolean
)

private data class CommitRef(
    val id: ObjectId,
    val commitTimeEpochSec: Long
)

private data class ClassScope(
    val name: String,
    val startDepth: Int
)

private data class LineLexState(
    var inBlockComment: Boolean = false,
    var inTripleQuotedString: Boolean = false
)

private data class ParsedLine(
    val commentStripped: String,
    val funScanLine: String,
    val codeOnly: String,
    val codeOpenBraces: Int,
    val codeCloseBraces: Int
)

private data class TestInfo(
    val testId: String,
    val fqcn: String,
    val methodName: String,
    val displayName: String?
)

private data class ScanStats(
    val commitsScanned: Int,
    val kotlinFilesScanned: Int,
    val fileVersionsParsed: Int,
    val newTestsFound: Int
)

private data class ScanResult(
    val stats: ScanStats,
    val byAuthor: Map<String, Int>,
    val examplesByAuthor: Map<String, List<TestInfo>>,
    val fromDate: LocalDate?,
    val toDate: LocalDate?,
    val branchDisplay: String
)

private class CliException(message: String) : RuntimeException(message)
private class RepoException(message: String) : RuntimeException(message)
private class BranchException(message: String) : RuntimeException(message)

object TestCreationCounter {
    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = try {
            run(args)
            0
        } catch (e: CliException) {
            System.err.println("Argument error: ${e.message}")
            printUsage()
            1
        } catch (e: RepoException) {
            System.err.println("Repository error: ${e.message}")
            2
        } catch (e: BranchException) {
            System.err.println("Branch error: ${e.message}")
            3
        } catch (e: Exception) {
            System.err.println("Unexpected error: ${e.message}")
            3
        }

        if (exitCode != 0) {
            exitProcess(exitCode)
        }
    }

    private fun run(args: Array<String>) {
        val options = parseArgs(args)
        if (options.help) {
            printUsage()
            return
        }

        val repository = openRepository(File("."))
        try {
            val (headId, branchDisplay) = resolveBranchHead(repository, options.branch)
            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val sinceInstant = options.sinceDays?.let { now.minus(it.toLong(), ChronoUnit.DAYS) }
            val sinceEpoch = sinceInstant?.epochSecond

            val commits = collectCommits(repository, headId, sinceEpoch)
            val fromDate = sinceInstant?.atZone(zone)?.toLocalDate()
            val toDate = if (options.mode == Mode.SINCE_DAYS) now.atZone(zone).toLocalDate() else null

            val result = scanCommits(
                repository = repository,
                commits = commits,
                verbose = options.verbose,
                fromDate = fromDate,
                toDate = toDate,
                branchDisplay = branchDisplay
            )

            printReport(options, result)
        } finally {
            repository.close()
        }
    }

    private fun parseArgs(args: Array<String>): CliOptions {
        var useAll = false
        var sinceDays: Int? = null
        var branch: String? = null
        var verbose = false
        var help = false

        var index = 0
        while (index < args.size) {
            when (val arg = args[index]) {
                "--all" -> useAll = true
                "--since-days" -> {
                    val value = args.getOrNull(index + 1)
                        ?: throw CliException("Missing value for --since-days")
                    val days = value.toIntOrNull()
                        ?: throw CliException("Invalid value for --since-days: '$value'")
                    if (days <= 0) {
                        throw CliException("--since-days must be a positive integer")
                    }
                    sinceDays = days
                    index++
                }
                "--branch" -> {
                    val value = args.getOrNull(index + 1)
                        ?: throw CliException("Missing value for --branch")
                    if (value.isBlank()) {
                        throw CliException("Branch name cannot be blank")
                    }
                    branch = value.trim()
                    index++
                }
                "--verbose" -> verbose = true
                "--help", "-h" -> help = true
                else -> throw CliException("Unknown argument: $arg")
            }
            index++
        }

        if (useAll && sinceDays != null) {
            throw CliException("Use either --all or --since-days N, not both")
        }

        val mode = if (useAll) Mode.ALL else Mode.SINCE_DAYS
        val effectiveSinceDays = if (mode == Mode.SINCE_DAYS) sinceDays ?: DEFAULT_SINCE_DAYS else null

        return CliOptions(
            mode = mode,
            sinceDays = effectiveSinceDays,
            branch = branch,
            verbose = verbose,
            help = help
        )
    }

    private fun printUsage() {
        println("Usage:")
        println("  --all                  Scan full history")
        println("  --since-days N         Scan commits in the last N days (default: $DEFAULT_SINCE_DAYS)")
        println("  --branch <name>        Branch/ref to scan (default: HEAD)")
        println("  --verbose              Print debug details")
        println("  --help                 Show this help")
    }

    private fun openRepository(cwd: File): Repository {
        val builder = FileRepositoryBuilder()
            .readEnvironment()
            .findGitDir(cwd)

        val gitDir = builder.gitDir
            ?: throw RepoException("No .git directory found from ${cwd.absolutePath}")

        return builder
            .setGitDir(gitDir)
            .build()
    }

    private fun resolveBranchHead(repository: Repository, branchArg: String?): Pair<ObjectId, String> {
        if (branchArg != null) {
            val byHeads = repository.resolve("refs/heads/$branchArg")
            val direct = repository.resolve(branchArg)
            val resolved = byHeads ?: direct
                ?: throw BranchException("Cannot resolve branch/ref '$branchArg'")

            return resolved to branchArg
        }

        val head = repository.resolve(Constants.HEAD)
            ?: throw BranchException("Cannot resolve HEAD")
        val display = try {
            repository.branch
        } catch (_: Exception) {
            "HEAD"
        }
        return head to display
    }

    private fun collectCommits(repository: Repository, headId: ObjectId, sinceEpoch: Long?): List<CommitRef> {
        val commits = mutableListOf<CommitRef>()

        RevWalk(repository).use { walk ->
            val headCommit = walk.parseCommit(headId)
            walk.markStart(headCommit)

            for (commit in walk) {
                val commitEpoch = commit.commitTime.toLong()
                if (sinceEpoch == null || commitEpoch >= sinceEpoch) {
                    commits += CommitRef(commit.id.copy(), commitEpoch)
                }
            }
        }

        return commits.sortedWith(compareBy<CommitRef> { it.commitTimeEpochSec }.thenBy { it.id.name() })
    }

    private fun scanCommits(
        repository: Repository,
        commits: List<CommitRef>,
        verbose: Boolean,
        fromDate: LocalDate?,
        toDate: LocalDate?,
        branchDisplay: String
    ): ScanResult {
        var commitsScanned = 0
        var kotlinFilesScanned = 0
        var fileVersionsParsed = 0
        var newTestsFound = 0

        val globalSeen = mutableSetOf<String>()
        val byAuthor = mutableMapOf<String, Int>()
        val examplesByAuthor = mutableMapOf<String, MutableList<TestInfo>>()

        RevWalk(repository).use { revWalk ->
            DiffFormatter(DisabledOutputStream.INSTANCE).use { diffFormatter ->
                diffFormatter.setRepository(repository)
                diffFormatter.setDetectRenames(true)

                for (commitRef in commits) {
                    val commit = revWalk.parseCommit(commitRef.id)
                    val parent = if (commit.parentCount > 0) revWalk.parseCommit(commit.getParent(0).id) else null
                    val author = resolveAuthor(commit)
                    commitsScanned++

                    val diffs = scanDiffs(diffFormatter, repository, parent, commit)
                    if (verbose) {
                        println("Commit ${commit.name.take(10)} by $author: ${diffs.size} changed file(s)")
                    }

                    for (diff in diffs) {
                        val oldPath = diff.oldPath.takeIf { it != DiffEntry.DEV_NULL && it.endsWith(".kt") }
                        val newPath = diff.newPath.takeIf { it != DiffEntry.DEV_NULL && it.endsWith(".kt") }
                        if (oldPath == null && newPath == null) {
                            continue
                        }

                        kotlinFilesScanned++

                        val oldContent = readFileAtCommit(repository, parent, oldPath, verbose)
                        val newContent = readFileAtCommit(repository, commit, newPath, verbose)

                        val testsInParent = if (oldContent != null) {
                            fileVersionsParsed++
                            KotlinTestExtractor.extract(oldContent, oldPath ?: newPath.orEmpty(), verbose)
                        } else {
                            emptyMap()
                        }

                        val testsInCommit = if (newContent != null) {
                            fileVersionsParsed++
                            KotlinTestExtractor.extract(newContent, newPath ?: oldPath.orEmpty(), verbose)
                        } else {
                            emptyMap()
                        }

                        val newlyAddedIds = testsInCommit.keys - testsInParent.keys

                        if (verbose && newlyAddedIds.isNotEmpty()) {
                            val printedPath = newPath ?: oldPath ?: "<unknown>"
                            println("  $printedPath -> added test IDs: ${newlyAddedIds.sorted().joinToString()}")
                        }

                        for (testId in newlyAddedIds.sorted()) {
                            if (!globalSeen.add(testId)) {
                                if (verbose) {
                                    println("  Skip duplicate across range: $testId")
                                }
                                continue
                            }

                            val testInfo = testsInCommit[testId] ?: continue
                            byAuthor[author] = (byAuthor[author] ?: 0) + 1
                            newTestsFound++

                            val authorExamples = examplesByAuthor.getOrPut(author) { mutableListOf() }
                            if (authorExamples.size < MAX_EXAMPLES_PER_AUTHOR) {
                                authorExamples += testInfo
                            }
                        }
                    }
                }
            }
        }

        return ScanResult(
            stats = ScanStats(
                commitsScanned = commitsScanned,
                kotlinFilesScanned = kotlinFilesScanned,
                fileVersionsParsed = fileVersionsParsed,
                newTestsFound = newTestsFound
            ),
            byAuthor = byAuthor.toMap(),
            examplesByAuthor = examplesByAuthor.mapValues { it.value.toList() },
            fromDate = fromDate,
            toDate = toDate,
            branchDisplay = branchDisplay
        )
    }

    private fun resolveAuthor(commit: RevCommit): String {
        val email = commit.authorIdent?.emailAddress?.trim().orEmpty()
        val name = commit.authorIdent?.name?.trim().orEmpty()
        return when {
            email.isNotBlank() -> email
            name.isNotBlank() -> name
            else -> "unknown"
        }
    }

    private fun scanDiffs(
        diffFormatter: DiffFormatter,
        repository: Repository,
        parent: RevCommit?,
        commit: RevCommit
    ): List<DiffEntry> {
        repository.newObjectReader().use { reader ->
            val newTree = CanonicalTreeParser().apply {
                reset(reader, commit.tree.id)
            }

            return if (parent == null) {
                diffFormatter.scan(EmptyTreeIterator(), newTree)
            } else {
                val oldTree = CanonicalTreeParser().apply {
                    reset(reader, parent.tree.id)
                }
                diffFormatter.scan(oldTree, newTree)
            }
        }
    }

    private fun readFileAtCommit(
        repository: Repository,
        commit: RevCommit?,
        path: String?,
        verbose: Boolean
    ): String? {
        if (commit == null || path == null || path == DiffEntry.DEV_NULL) {
            return null
        }

        return try {
            TreeWalk.forPath(repository, path, commit.tree)?.use { treeWalk ->
                val objectId = treeWalk.getObjectId(0)
                val loader = repository.open(objectId)
                loader.getBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            if (verbose) {
                println("  Failed to read '$path' in commit ${commit.name.take(10)}: ${e.message}")
            }
            null
        }
    }

    private fun printReport(options: CliOptions, result: ScanResult) {
        println("=== Test creation report ===")
        when (options.mode) {
            Mode.ALL -> println("Mode: all")
            Mode.SINCE_DAYS -> println(
                "Mode: since-days=${options.sinceDays} (from ${result.fromDate} to ${result.toDate})"
            )
        }
        println("Branch: ${result.branchDisplay}")
        println("Commits scanned: ${result.stats.commitsScanned}")
        println("Kotlin files scanned: ${result.stats.kotlinFilesScanned}")
        println("File versions parsed: ${result.stats.fileVersionsParsed}")
        println("New tests found: ${result.stats.newTestsFound}")
        println()

        println("By author:")
        val sortedAuthors = result.byAuthor.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        if (sortedAuthors.isEmpty()) {
            println("* (no new tests found)")
        } else {
            for ((author, count) in sortedAuthors) {
                println("* $author : $count")
            }
        }

        if (options.verbose && sortedAuthors.isNotEmpty()) {
            println()
            println("Examples:")
            for ((author, _) in sortedAuthors) {
                val tests = result.examplesByAuthor[author].orEmpty()
                if (tests.isEmpty()) {
                    continue
                }
                println("$author:")
                for (test in tests) {
                    val displayNameSuffix = test.displayName?.let { " (DisplayName=\"$it\")" } ?: ""
                    println("* ${test.testId}$displayNameSuffix")
                }
            }
        }
    }
}

private object KotlinTestExtractor {
    fun extract(content: String, filePath: String, verbose: Boolean): Map<String, TestInfo> {
        val packageName = PACKAGE_REGEX.find(content)?.groupValues?.get(1).orEmpty()
        val fallbackClassName = filePath.substringAfterLast('/').substringBeforeLast('.').ifBlank { "UnknownFileClass" }

        val lines = content.lines()
        val tests = linkedMapOf<String, TestInfo>()
        val classStack = mutableListOf<ClassScope>()

        var braceDepth = 0
        var pendingClassName: String? = null
        var usedFallbackClassName = false
        val annotationBuffer = mutableListOf<String>()
        var linesSinceLastAnnotation = 0
        val lexState = LineLexState()

        for (line in lines) {
            val parsedLine = parseKotlinLine(line, lexState)
            val lineWithoutComment = parsedLine.commentStripped
            val structureLine = parsedLine.codeOnly
            val trimmed = structureLine.trim()
            val classMatch = CLASS_DECL_REGEX.find(structureLine)

            if (pendingClassName != null) {
                if (classMatch != null) {
                    // Previous class declaration had no body; do not bind it to another declaration's braces.
                    pendingClassName = null
                } else if (parsedLine.codeOpenBraces > 0) {
                    classStack += ClassScope(name = pendingClassName!!, startDepth = braceDepth + 1)
                    pendingClassName = null
                }
            }

            val funMatch = FUN_DECL_REGEX.find(parsedLine.funScanLine)
            if (trimmed.startsWith("@")) {
                annotationBuffer += lineWithoutComment
                linesSinceLastAnnotation = 0
            } else if (annotationBuffer.isNotEmpty() && funMatch == null) {
                linesSinceLastAnnotation++
                if (linesSinceLastAnnotation <= MAX_ANNOTATION_SCAN_LINES) {
                    annotationBuffer += lineWithoutComment
                } else {
                    annotationBuffer.clear()
                }
            }

            if (funMatch != null) {
                val methodRaw = funMatch.groupValues[1]
                val methodName = normalizeMethodName(methodRaw)
                val annotationText = annotationBuffer.joinToString("\n")
                if (TEST_ANNOTATION_REGEX.containsMatchIn(annotationText)) {
                    val classChain = if (classStack.isEmpty()) {
                        usedFallbackClassName = true
                        fallbackClassName
                    } else {
                        classStack.joinToString(".") { it.name }
                    }
                    val fqcn = buildFqcn(packageName, classChain)
                    val testId = "$fqcn#$methodName"
                    val displayName = extractDisplayName(annotationText)
                    tests.putIfAbsent(
                        testId,
                        TestInfo(
                            testId = testId,
                            fqcn = fqcn,
                            methodName = methodName,
                            displayName = displayName
                        )
                    )
                }
                annotationBuffer.clear()
                linesSinceLastAnnotation = 0
            }

            if (classMatch != null && !trimmed.startsWith("companion object")) {
                val className = classMatch.groupValues[2]
                val declarationTail = structureLine.substring(classMatch.range.last + 1)
                val tailOpenBraces = declarationTail.count { it == '{' }
                val tailCloseBraces = declarationTail.count { it == '}' }
                if (tailOpenBraces > tailCloseBraces) {
                    classStack += ClassScope(name = className, startDepth = braceDepth + 1)
                } else {
                    pendingClassName = className
                }
            }

            braceDepth = (braceDepth + parsedLine.codeOpenBraces - parsedLine.codeCloseBraces).coerceAtLeast(0)

            while (classStack.isNotEmpty() && braceDepth < classStack.last().startDepth) {
                classStack.removeAt(classStack.lastIndex)
            }
        }

        if (verbose) {
            if (tests.isEmpty() && "@Test" in content) {
                println("  Parser note: no test functions extracted from $filePath (possible parser limitation)")
            }
            if (usedFallbackClassName) {
                println("  Parser note: fallback class name '$fallbackClassName' used for $filePath")
            }
        }

        return tests
    }

    private fun normalizeMethodName(raw: String): String {
        return if (raw.startsWith("`") && raw.endsWith("`") && raw.length >= 2) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
    }

    private fun buildFqcn(packageName: String, classChain: String): String {
        return if (packageName.isBlank()) classChain else "$packageName.$classChain"
    }

    private fun extractDisplayName(annotationText: String): String? {
        val displayMatch = DISPLAY_NAME_REGEX.find(annotationText) ?: return null
        val rawArg = displayMatch.groupValues[1]
        val literal = STRING_LITERAL_REGEX.find(rawArg)?.groupValues?.get(1)
        return literal?.replace("\\\"", "\"")?.replace("\\n", "\n")?.trim()
            ?: rawArg.trim().ifBlank { null }
    }

    private fun parseKotlinLine(line: String, state: LineLexState): ParsedLine {
        val stripped = StringBuilder()
        val funScan = StringBuilder()
        val codeOnly = StringBuilder()
        var codeOpenBraces = 0
        var codeCloseBraces = 0

        var i = 0
        var inBacktick = false
        var inString = false
        var stringEscape = false

        while (i < line.length) {
            if (state.inBlockComment) {
                if (i + 1 < line.length && line[i] == '*' && line[i + 1] == '/') {
                    state.inBlockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }

            if (state.inTripleQuotedString) {
                if (i + 2 < line.length && line[i] == '"' && line[i + 1] == '"' && line[i + 2] == '"') {
                    stripped.append("\"\"\"")
                    funScan.append("   ")
                    codeOnly.append("   ")
                    state.inTripleQuotedString = false
                    i += 3
                } else {
                    stripped.append(line[i])
                    funScan.append(' ')
                    codeOnly.append(' ')
                    i++
                }
                continue
            }

            if (inString) {
                val ch = line[i]
                stripped.append(ch)
                funScan.append(' ')
                codeOnly.append(' ')

                if (ch == '\\' && !stringEscape) {
                    stringEscape = true
                } else {
                    if (ch == '"' && !stringEscape) {
                        inString = false
                    }
                    stringEscape = false
                }
                i++
                continue
            }

            if (inBacktick) {
                val ch = line[i]
                stripped.append(ch)
                funScan.append(ch)
                codeOnly.append(' ')
                if (ch == '`') {
                    inBacktick = false
                }
                i++
                continue
            }

            if (i + 1 < line.length && line[i] == '/' && line[i + 1] == '/') {
                break
            }

            if (i + 1 < line.length && line[i] == '/' && line[i + 1] == '*') {
                state.inBlockComment = true
                i += 2
                continue
            }

            if (i + 2 < line.length && line[i] == '"' && line[i + 1] == '"' && line[i + 2] == '"') {
                stripped.append("\"\"\"")
                funScan.append("   ")
                codeOnly.append("   ")
                state.inTripleQuotedString = true
                i += 3
                continue
            }

            val ch = line[i]
            when (ch) {
                '"' -> {
                    inString = true
                    stringEscape = false
                    stripped.append(ch)
                    funScan.append(' ')
                    codeOnly.append(' ')
                    i++
                }
                '`' -> {
                    inBacktick = true
                    stripped.append(ch)
                    funScan.append(ch)
                    codeOnly.append(' ')
                    i++
                }
                '\'' -> {
                    stripped.append(ch)
                    funScan.append(' ')
                    codeOnly.append(' ')
                    i++
                    var escape = false
                    while (i < line.length) {
                        val charCh = line[i]
                        stripped.append(charCh)
                        funScan.append(' ')
                        codeOnly.append(' ')
                        i++
                        if (charCh == '\\' && !escape) {
                            escape = true
                            continue
                        }
                        if (charCh == '\'' && !escape) {
                            break
                        }
                        escape = false
                    }
                }
                else -> {
                    stripped.append(ch)
                    funScan.append(ch)
                    codeOnly.append(ch)
                    if (ch == '{') {
                        codeOpenBraces++
                    } else if (ch == '}') {
                        codeCloseBraces++
                    }
                    i++
                }
            }
        }

        return ParsedLine(
            commentStripped = stripped.toString(),
            funScanLine = funScan.toString(),
            codeOnly = codeOnly.toString(),
            codeOpenBraces = codeOpenBraces,
            codeCloseBraces = codeCloseBraces
        )
    }
}
