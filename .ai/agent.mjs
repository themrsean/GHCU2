import fs from "fs";
import path from "path";
import {execFile} from "child_process";
import dotenv from "dotenv";
import OpenAI from "openai";
import readline from "readline/promises";
import {stdin as inputStream, stdout as outputStream} from "process";

dotenv.config();

const execFileAsync = (file, args, options) => {
    const resolvedOptions = {
        ...options,
        encoding: "utf8",
    };

    return new Promise((resolve, reject) => {
        execFile(file, args, resolvedOptions, (error, stdout, stderr) => {
            const stdoutText = typeof stdout === "string" ? stdout : String(stdout ?? "");
            const stderrText = typeof stderr === "string" ? stderr : String(stderr ?? "");

            if (error) {
                error.stdout = stdoutText;
                error.stderr = stderrText;
                reject(error);
            } else {
                resolve({stdout: stdoutText, stderr: stderrText});
            }
        });
    });
};

const MODEL_NAME = "gpt-5-mini";
const MAX_TOOL_TURNS = 10;
const MAX_FILE_BYTES = 200_000;
const MAX_PROCESS_BUFFER_BYTES = 10_000_000;
const MAX_SEARCH_RESULTS = 50;

const MODE_EXPLAIN = "explain";
const MODE_PLAN = "plan";
const MODE_DEBUG = "debug";
const MODE_TEST = "test";
const MODE_REFACTOR = "refactor";

const DEFAULT_MODE = MODE_DEBUG;
const REPO_ROOT = process.cwd();
const ALLOWED_ROOT = REPO_ROOT;

const TEST_OUTPUT_DIR = "out/test/GHCU2v2";
const PROD_OUTPUT_DIR = "out/production/GHCU2v2";
const JUNIT_JAR = "lib/junit-platform-console-standalone-6.0.1.jar";
const LIB_DIR = "lib";
const JAVAFX_LIB_DIR = "lib/javafx-sdk/lib";
const MAX_JAVA_FILES = 20000;
const MAX_WRITE_BYTES = 200_000;
const MAX_PATCH_BYTES = 200_000;
const WRITE_ALLOWED_PREFIXES_TEST = [
    "src/test/java/",
    "src/test/resources/"
];
const WRITE_ALLOWED_PREFIXES_REFACTOR = [
    ...WRITE_ALLOWED_PREFIXES_TEST,
    "src/main/java/"
];
const TOOL_LIST_FILES = "list_files";
const TOOL_READ_FILE = "read_file";
const TOOL_SEARCH_REPO = "search_repo";
const TOOL_COMPILE_PROD = "compile_prod";
const TOOL_COMPILE_TESTS = "compile_tests";
const TOOL_RUN_TESTS = "run_tests";
const TOOL_WRITE_FILE = "write_file";
const TOOL_APPLY_PATCH = "apply_patch";
const ROLE_USER = "user";
const ROLE_TOOL = "tool";
const LOG_DIR = "out/logs";
const DEFAULT_LOG_FILE = "agent.log";

const SESSION_STATUS_RUNNING = "running";
const SESSION_STATUS_NEEDS_USER_INPUT = "needs_user_input";
const SESSION_STATUS_COMPLETED = "completed";
const SESSION_STATUS_FAILED = "failed";

const SESSION_PHASE_INTERACTIVE_INSPECTION = "interactive_inspection";
const SESSION_PHASE_AUTONOMOUS_EXECUTION = "autonomous_execution";
const SESSION_PHASE_PAUSED_FOR_USER = "paused_for_user";
const SESSION_PHASE_COMPLETED = "completed";
const SESSION_PHASE_FAILED = "failed";
const SESSION_PHASE_TASK_PREPARATION = "task_preparation";

const COMMAND_TASK_PREFIX = "/task ";
const TASK_STATUS_IDLE = "idle";
const TASK_STATUS_READY = "ready";
const TASK_STATUS_RUNNING = "running";
const TASK_STATUS_BLOCKED = "blocked";
const TASK_STATUS_COMPLETED = "completed";

const PAUSE_REASON_ASSISTANT_QUESTION = "assistant_question";
const PAUSE_REASON_NO_PROGRESS = "no_progress";
const PAUSE_REASON_NO_ARTIFACT_WRITTEN = "no_artifact_written";
const PAUSE_REASON_PARTIAL_COMPLETION = "partial_completion";

const GENERAL_WORK_ITEM_TARGET = "<general>";
const WORK_ITEM_STATUS_PENDING = "pending";
const WORK_ITEM_STATUS_RUNNING = "running";
const WORK_ITEM_STATUS_COMPLETED = "completed";
const WORK_ITEM_STATUS_BLOCKED = "blocked";
const WORK_ITEM_STATUS_SKIPPED = "skipped";

const MAX_NO_PROGRESS_CYCLES = 2;
const INTERACTIVE_FLAG = "--interactive";
const COMMAND_CONTINUE = "/continue";
const COMMAND_STATUS = "/status";
const COMMAND_HISTORY = "/history";
const COMMAND_STOP = "/stop";
const COMMAND_ANSWER_PREFIX = "/answer ";
const COMMAND_PROMPT_PREFIX = "/prompt ";
const COMMAND_ASK_PREFIX = "/ask ";
const COMMAND_WHY = "/why";
const HISTORY_MESSAGE_COUNT = 8;

const createSession = (userPrompt, isInteractive) => {
    const mode = parseMode(userPrompt);
    const promptWithoutMode = stripModePrefix(userPrompt);

    const input = [
        {role: "system", content: buildSystemInstructions(mode)},
        {role: ROLE_USER, content: promptWithoutMode}
    ];

    const targetsFromPrompt = extractTargetsFromPrompt(promptWithoutMode);
    const isInspectionMode = mode === MODE_PLAN || mode === MODE_DEBUG || mode === MODE_EXPLAIN;

    const initialPhase =
        isInteractive && isInspectionMode
            ? SESSION_PHASE_INTERACTIVE_INSPECTION
            : SESSION_PHASE_AUTONOMOUS_EXECUTION;

    return {
        mode,
        phase: initialPhase,
        interactive: isInteractive,
        currentTask: createEmptyTaskState(),
        promptWithoutMode,
        originalPrompt: userPrompt,
        input,
        targetEvidence: {
            targets: targetsFromPrompt,
            foundBySearch: new Set(),
            foundByRead: new Set(),
            readFiles: new Set()
        },
        duplicateBudget: {
            seenToolCallSignatures: new Map(),
            maxDuplicateToolCalls: 2,
            maxTotalDuplicateSkips: 8,
            totalDuplicateSkips: 0
        },
        iteration: 0,
        consecutiveNoProgressCycles: 0,
        lastBuildResult: null,
        lastLoopResult: null,
        status: SESSION_STATUS_RUNNING,
        pendingQuestion: null
    };
};

const createEmptyTaskState = () => {
    return {
        status: TASK_STATUS_IDLE,
        rawText: "",
        mode: "",
        summary: "",
        successCriteria: [],
        requestedTargets: [],
        completedTargets: [],
        blockedTargets: [],
        workItems: [],
        writtenFiles: [],
        patchedFiles: [],
        lastBlockingReason: "",
        pauseReason: "",
        pauseQuestion: ""
    };
};

const createWorkItem = (target, description) => {
    return {
        target,
        description,
        status: WORK_ITEM_STATUS_PENDING,
        notes: "",
        writtenFiles: [],
        patchedFiles: [],
        blockingReason: ""
    };
};

const createTaskFromPrompt = (taskPrompt) => {
    const mode = parseMode(taskPrompt);
    const promptWithoutMode = stripModePrefix(taskPrompt);
    const requestedTargets = extractTargetsFromPrompt(promptWithoutMode);

    const workItems = [];
    for (let i = 0; i < requestedTargets.length; i += 1) {
        const target = requestedTargets[i];
        workItems.push(createWorkItem(target, `Complete requested work for ${target}`));
    }

    const successCriteria = [];
    successCriteria.push("Use repo-grounded evidence before code changes.");

    if (mode === MODE_TEST) {
        successCriteria.push("Create or modify meaningful JUnit tests.");
        successCriteria.push("Compile production and test sources successfully.");
        successCriteria.push("Run tests successfully.");
    } else if (mode === MODE_REFACTOR) {
        successCriteria.push("Apply only behavior-preserving refactors.");
        successCriteria.push("Compile production and test sources successfully.");
        successCriteria.push("Run tests successfully.");
    } else if (mode === MODE_PLAN) {
        successCriteria.push("Produce a grounded implementation plan.");
    } else if (mode === MODE_DEBUG) {
        successCriteria.push("Produce a grounded diagnosis.");
    } else {
        successCriteria.push("Complete the requested repo-grounded task.");
    }

    const task = createEmptyTaskState();
    task.status = TASK_STATUS_READY;
    task.rawText = taskPrompt;
    task.mode = mode;
    task.summary = promptWithoutMode;
    task.successCriteria = successCriteria;
    task.requestedTargets = requestedTargets;
    task.workItems = workItems;

    if (requestedTargets.length === 0) {
        task.workItems.push(createWorkItem(GENERAL_WORK_ITEM_TARGET, promptWithoutMode));
    }

    return task;
};

const appendUnique = (items, value) => {
    if (!items.includes(value)) {
        items.push(value);
    }
};

const markTaskFileWrite = (session, filePath, isPatch) => {
    if (isPatch) {
        appendUnique(session.currentTask.patchedFiles, filePath);
    } else {
        appendUnique(session.currentTask.writtenFiles, filePath);
    }
};

const markWorkItemRunningIfMatched = (workItem, targetEvidence) => {
    const target = workItem.target;
    if (target === GENERAL_WORK_ITEM_TARGET) {
        return;
    }

    const foundByRead = targetEvidence.foundByRead.has(target);
    const foundBySearch = targetEvidence.foundBySearch.has(target);
    const hasEvidence = foundByRead || foundBySearch;

    if (hasEvidence && workItem.status === WORK_ITEM_STATUS_PENDING) {
        workItem.status = WORK_ITEM_STATUS_RUNNING;
    }
};

const summarizeTaskProgress = (task) => {
    const completed = [];
    const blocked = [];
    const pending = [];

    for (let i = 0; i < task.workItems.length; i += 1) {
        const item = task.workItems[i];
        if (item.status === WORK_ITEM_STATUS_COMPLETED) {
            completed.push(item.target);
        } else if (item.status === WORK_ITEM_STATUS_BLOCKED) {
            blocked.push(item.target);
        } else {
            pending.push(item.target);
        }
    }

    return {completed, blocked, pending};
};

const allWorkItemsResolved = (task) => {
    for (let i = 0; i < task.workItems.length; i += 1) {
        const status = task.workItems[i].status;
        const resolved =
            status === WORK_ITEM_STATUS_COMPLETED ||
            status === WORK_ITEM_STATUS_BLOCKED ||
            status === WORK_ITEM_STATUS_SKIPPED;

        if (!resolved) {
            return false;
        }
    }
    return true;
};

const pauseSessionForUser = (session, reason, question) => {
    session.phase = SESSION_PHASE_PAUSED_FOR_USER;
    session.status = SESSION_STATUS_NEEDS_USER_INPUT;
    session.pendingQuestion = question;
    session.currentTask.pauseReason = reason;
    session.currentTask.pauseQuestion = question;
    session.currentTask.lastBlockingReason = reason;
};

const createConsoleInterface = () => {
    return readline.createInterface({
        input: inputStream,
        output: outputStream
    });
};

const showSessionStatus = (session, logger) => {
    const targets = session.targetEvidence.targets;
    const targetText = targets.length > 0 ? targets.join(", ") : "<none>";
    const lastBuildPhase = session.lastBuildResult?.phase ?? "<none>";
    const lastBuildOk = session.lastBuildResult?.ok ?? false;
    const writesApplied = session.lastLoopResult?.writesApplied ?? 0;

    logger.log("----- SESSION STATUS -----");
    logger.log(`Mode: ${session.mode}`);
    logger.log(`Phase: ${session.phase}`);
    logger.log(`Iteration: ${session.iteration}`);
    logger.log(`Targets: ${targetText}`);
    logger.log(`Last build phase: ${lastBuildPhase}`);
    logger.log(`Last build ok: ${lastBuildOk}`);
    logger.log(`Last writesApplied: ${writesApplied}`);
    logger.log(`No-progress cycles: ${session.consecutiveNoProgressCycles}`);
    const progress = summarizeTaskProgress(session.currentTask);
    logger.log(`Completed work items: ${progress.completed.join(", ") || "<none>"}`);
    logger.log(`Pending work items: ${progress.pending.join(", ") || "<none>"}`);
    logger.log(`Blocked work items: ${progress.blocked.join(", ") || "<none>"}`);
    logger.log(`Status: ${session.status}`);
    logger.log("--------------------------");
};

const isInspectionMode = (mode) => {
    return mode === MODE_EXPLAIN || mode === MODE_PLAN || mode === MODE_DEBUG;
};

const isWriteMode = (mode) => {
    return mode === MODE_TEST || mode === MODE_REFACTOR;
};

const shouldRemainInteractiveAfterCycle = (session) => {
    return session.interactive && isInspectionMode(session.mode);
};

const replaceSystemMessageForMode = (session, newMode) => {
    if (session.input.length > 0) {
        session.input[0] = {
            role: "system",
            content: buildSystemInstructions(newMode)
        };
    } else {
        session.input.push({
            role: "system",
            content: buildSystemInstructions(newMode)
        });
    }
};

const resetExecutionStateForNewTask = (session, taskPrompt) => {
    const task = createTaskFromPrompt(taskPrompt);

    session.mode = task.mode;
    session.promptWithoutMode = task.summary;
    session.currentTask = task;
    session.phase = SESSION_PHASE_TASK_PREPARATION;

    session.targetEvidence = {
        targets: [...task.requestedTargets],
        foundBySearch: new Set(),
        foundByRead: new Set(),
        readFiles: new Set()
    };

    session.duplicateBudget = {
        seenToolCallSignatures: new Map(),
        maxDuplicateToolCalls: 2,
        maxTotalDuplicateSkips: 8,
        totalDuplicateSkips: 0
    };

    session.iteration = 0;
    session.consecutiveNoProgressCycles = 0;
    session.lastBuildResult = null;
    session.lastLoopResult = null;
    session.status = SESSION_STATUS_RUNNING;
    session.pendingQuestion = null;

    replaceSystemMessageForMode(session, task.mode);

    session.input.push({
        role: ROLE_USER,
        content:
            `New execution task:\n${taskPrompt}\n\n` +
            `Tracked targets: ${task.requestedTargets.length > 0 ? task.requestedTargets.join(", ") : GENERAL_WORK_ITEM_TARGET}`
    });
};

const showRecentHistory = (session, logger) => {
    const startIndex = Math.max(0, session.input.length - HISTORY_MESSAGE_COUNT);

    logger.log("----- RECENT HISTORY -----");
    for (let i = startIndex; i < session.input.length; i += 1) {
        const msg = session.input[i];
        const role = msg?.role ?? "<unknown>";
        const contentRaw = typeof msg?.content === "string" ? msg.content : JSON.stringify(msg?.content ?? "");
        const content = contentRaw.length > 500 ? `${contentRaw.slice(0, 500)}...` : contentRaw;
        logger.log(`${role}: ${content}`);
    }
    logger.log("--------------------------");
};

const promptUserForSessionInput = async (session, logger, rl) => {
    if (session.pendingQuestion) {
        logger.log("Agent needs input:");
        logger.log(session.pendingQuestion);
    } else {
        logger.log("Agent paused and can accept guidance.");
    }

    logger.log(`Commands: ${COMMAND_CONTINUE}, ${COMMAND_STATUS}, ${COMMAND_HISTORY}, ${COMMAND_STOP}, ${COMMAND_WHY}, ${COMMAND_ANSWER_PREFIX}<text>, ${COMMAND_PROMPT_PREFIX}<text>, ${COMMAND_ASK_PREFIX}<question>, ${COMMAND_TASK_PREFIX}<task>`);
    const answer = await rl.question("> ");
    const trimmed = answer.trim();

    if (trimmed === COMMAND_STATUS) {
        showSessionStatus(session, logger);
        return {action: "retry_prompt"};
    }

    if (trimmed === COMMAND_HISTORY) {
        showRecentHistory(session, logger);
        return {action: "retry_prompt"};
    }

    if (trimmed === COMMAND_STOP) {
        return {action: "stop"};
    }

    if (trimmed === COMMAND_CONTINUE || trimmed.length === 0) {
        return {action: "continue"};
    }

    if (trimmed.startsWith(COMMAND_ANSWER_PREFIX)) {
        const guidance = trimmed.slice(COMMAND_ANSWER_PREFIX.length).trim();
        return {action: "append_user_message", content: guidance};
    }

    if (trimmed.startsWith(COMMAND_PROMPT_PREFIX)) {
        const guidance = trimmed.slice(COMMAND_PROMPT_PREFIX.length).trim();
        return {action: "append_user_message", content: guidance};
    }

    if (trimmed === COMMAND_WHY) {
        return {action: "ask_why"};
    }

    if (trimmed.startsWith(COMMAND_ASK_PREFIX)) {
        const question = trimmed.slice(COMMAND_ASK_PREFIX.length).trim();
        return {action: "ask_assistant", content: question};
    }

    if (trimmed.startsWith(COMMAND_TASK_PREFIX)) {
        const taskText = trimmed.slice(COMMAND_TASK_PREFIX.length).trim();
        return {action: "start_task", content: taskText};
    }

    return {action: "append_user_message", content: trimmed};
};

const appendAssistantResponse = async (session, logger) => {
    const response = await openai.chat.completions.create({
        model: MODEL_NAME,
        messages: session.input
    });

    const assistantMessage = response.choices[0].message;
    const assistantText = typeof assistantMessage?.content === "string"
        ? assistantMessage.content.trim()
        : "";

    session.input.push({
        role: "assistant",
        content: assistantText
    });

    if (assistantText.length > 0) {
        logger.log("Assistant:");
        logger.log(assistantText);
    } else {
        logger.log("[assistant returned no text]");
    }
};

const askAssistantInSession = async (session, logger, question) => {
    const content = typeof question === "string" ? question.trim() : "";

    if (content.length === 0) {
        logger.log("No question provided.");
        return;
    }

    const useTools = askModeAllowsTools(session.mode);

    session.input.push({
        role: ROLE_USER,
        content
    });

    if (useTools) {
        await askAssistantWithToolsInSession(session, logger);
    } else {
        await appendAssistantResponse(session, logger);
    }
};

const askWhyInSession = async (session, logger) => {
    const diagnosticSummary = buildSessionDiagnosticSummary(session);

    const whyQuestion =
        `${diagnosticSummary}\n\n` +
        "Explain the current state of the task session. " +
        "State clearly:\n" +
        "1) what has been completed,\n" +
        "2) what has not been completed,\n" +
        "3) why it was not completed,\n" +
        "4) what the next step should be.\n" +
        "Be specific and concrete.";

    session.input.push({
        role: ROLE_USER,
        content: whyQuestion
    });

    await appendAssistantResponse(session, logger);
};

const askModeAllowsTools = (mode) => {
    return mode === MODE_EXPLAIN || mode === MODE_PLAN || mode === MODE_DEBUG;
};

const askAssistantWithToolsInSession = async (session, logger) => {
    const activeToolsAll = toolsForMode(session.mode, tools);
    session.lastLoopResult = await runToolLoop({
        session,
        toolset: activeToolsAll,
        toolChoice: "auto",
        logger,
        callLocalTool,
        printToolResult,
        maxTurns: MAX_TOOL_TURNS
    });
};

const assistantSeemsToNeedHelp = (input) => {
    let lastAssistantText = "";

    for (let i = input.length - 1; i >= 0; i -= 1) {
        const msg = input[i];
        if (msg?.role === "assistant") {
            const content = typeof msg?.content === "string" ? msg.content.trim() : "";
            if (content.length > 0) {
                lastAssistantText = content;
                i = -1;
            }
        }
    }

    if (lastAssistantText.length === 0) {
        return {needsHelp: false, question: null};
    }

    const lower = lastAssistantText.toLowerCase();
    const hasQuestionMark = lastAssistantText.includes("?");
    const asksForClarification =
        lower.includes("need clarification") ||
        lower.includes("do you want") ||
        lower.includes("should i") ||
        lower.includes("which should") ||
        lower.includes("which one") ||
        lower.includes("i am not sure") ||
        lower.includes("i'm not sure") ||
        hasQuestionMark;

    if (asksForClarification) {
        return {needsHelp: true, question: lastAssistantText};
    }

    return {needsHelp: false, question: null};
};

const buildSessionDiagnosticSummary = (session) => {
    const targets = Array.isArray(session?.targetEvidence?.targets)
        ? session.targetEvidence.targets
        : [];

    const readFiles = session?.targetEvidence?.readFiles instanceof Set
        ? Array.from(session.targetEvidence.readFiles)
        : [];

    const lastBuildPhase = session?.lastBuildResult?.phase ?? "<none>";
    const lastBuildOk = session?.lastBuildResult?.ok ?? false;
    const lastWritesApplied = session?.lastLoopResult?.writesApplied ?? 0;
    const lastHadToolCalls = session?.lastLoopResult?.hadToolCalls ?? false;
    const pendingQuestion = typeof session?.pendingQuestion === "string" && session.pendingQuestion.length > 0
        ? session.pendingQuestion
        : "<none>";

    const targetText = targets.length > 0 ? targets.join(", ") : "<none>";
    const readFilesPreviewCount = 10;
    const readFilesPreview = readFiles.slice(0, readFilesPreviewCount);
    const readFilesText = readFilesPreview.length > 0 ? readFilesPreview.join(", ") : "<none>";

    return [
        "Current task session diagnostic summary:",
        `Mode: ${session?.mode ?? "<unknown>"}`,
        `Phase: ${session?.phase ?? "<unknown>"}`,
        `Iteration: ${session?.iteration ?? 0}`,
        `Status: ${session?.status ?? "<unknown>"}`,
        `Targets: ${targetText}`,
        `Last build phase: ${lastBuildPhase}`,
        `Last build ok: ${lastBuildOk}`,
        `Last writesApplied: ${lastWritesApplied}`,
        `Last hadToolCalls: ${lastHadToolCalls}`,
        `No-progress cycles: ${session?.consecutiveNoProgressCycles ?? 0}`,
        `Pending question: ${pendingQuestion}`,
        `Read files (up to ${readFilesPreviewCount}): ${readFilesText}`
    ].join("\n");
};

const prepareTaskExecution = (session) => {
    session.phase = SESSION_PHASE_AUTONOMOUS_EXECUTION;
    session.currentTask.status = TASK_STATUS_RUNNING;
};

const completeInteractiveInspectionCycle = (session) => {
    session.phase = SESSION_PHASE_PAUSED_FOR_USER;
    session.status = SESSION_STATUS_NEEDS_USER_INPUT;
    session.pendingQuestion =
        "Inspection cycle complete. Ask another question, give more guidance, or use /task to start an execution task.";
};

const updateWorkItemsFromEvidence = (session) => {
    const workItems = session.currentTask.workItems;
    for (let i = 0; i < workItems.length; i += 1) {
        markWorkItemRunningIfMatched(workItems[i], session.targetEvidence);
    }
};

const markTaskCompletedFromBuild = (session) => {
    const workItems = session.currentTask.workItems;
    session.currentTask.completedTargets = [];
    session.currentTask.blockedTargets = [];

    for (let i = 0; i < workItems.length; i += 1) {
        const item = workItems[i];
        const hasArtifact =
            item.writtenFiles.length > 0 ||
            item.patchedFiles.length > 0;

        if (hasArtifact) {
            item.status = WORK_ITEM_STATUS_COMPLETED;
            appendUnique(session.currentTask.completedTargets, item.target);
        } else if (
            item.status === WORK_ITEM_STATUS_PENDING ||
            item.status === WORK_ITEM_STATUS_RUNNING
        ) {
            item.status = WORK_ITEM_STATUS_BLOCKED;
            item.blockingReason = "No artifact was created for this work item.";
            appendUnique(session.currentTask.blockedTargets, item.target);
        }
    }

    session.currentTask.status = allWorkItemsResolved(session.currentTask)
        ? TASK_STATUS_COMPLETED
        : TASK_STATUS_BLOCKED;
};

const markPartialCompletionAndPause = (session) => {
    const progress = summarizeTaskProgress(session.currentTask);
    const pendingText = progress.pending.length > 0 ? progress.pending.join(", ") : "<none>";
    const completedText = progress.completed.length > 0 ? progress.completed.join(", ") : "<none>";

    const question =
        `Task partially completed.\n` +
        `Completed: ${completedText}\n` +
        `Still unresolved: ${pendingText}\n` +
        `Ask a question, clarify requirements, or say /continue.`;

    pauseSessionForUser(session, PAUSE_REASON_PARTIAL_COMPLETION, question);
};

const createCycleResult = (status, reason, question) => {
    return {status, reason, question};
};

const markSessionCompleted = (session, reason) => {
    session.phase = SESSION_PHASE_COMPLETED;
    session.status = SESSION_STATUS_COMPLETED;
    session.pendingQuestion = null;

    return createCycleResult(SESSION_STATUS_COMPLETED, reason, null);
};

const returnTaskCompletionToInspection = (session) => {
    session.mode = MODE_DEBUG;
    replaceSystemMessageForMode(session, session.mode);

    const question =
        "Task completed. Ask about the results, inspect the repo again, or use /task for another task.";

    session.phase = SESSION_PHASE_INTERACTIVE_INSPECTION;
    session.status = SESSION_STATUS_NEEDS_USER_INPUT;
    session.pendingQuestion = question;

    return createCycleResult(
        SESSION_STATUS_NEEDS_USER_INPUT,
        "task_completed_return_to_inspection",
        question
    );
};

const handleAssistantClarificationPause = (session, clarificationQuestion) => {
    pauseSessionForUser(session, PAUSE_REASON_ASSISTANT_QUESTION, clarificationQuestion);

    return createCycleResult(
        SESSION_STATUS_NEEDS_USER_INPUT,
        PAUSE_REASON_ASSISTANT_QUESTION,
        clarificationQuestion
    );
};

const handleInspectionModeCycleCompletion = (session) => {
    if (shouldRemainInteractiveAfterCycle(session)) {
        completeInteractiveInspectionCycle(session);
        return createCycleResult(
            SESSION_STATUS_NEEDS_USER_INPUT,
            "interactive_inspection_pause",
            session.pendingQuestion
        );
    }

    return markSessionCompleted(session, "inspection_complete");
};

const handleNoArtifactWrittenPause = (session) => {
    const question =
        "I passed the build, but I still have not created any test files. " +
        "Please clarify what exact behavior you want covered, or say /continue to let me try again.";

    pauseSessionForUser(session, PAUSE_REASON_NO_ARTIFACT_WRITTEN, question);

    return createCycleResult(
        SESSION_STATUS_NEEDS_USER_INPUT,
        PAUSE_REASON_NO_ARTIFACT_WRITTEN,
        question
    );
};

const handleNoProgressPause = (session) => {
    const question =
        "I am not making progress on the task with the current instructions. " +
        "Please clarify the expected behavior, constraints, or preferred testing approach.";

    pauseSessionForUser(session, PAUSE_REASON_NO_PROGRESS, question);

    return createCycleResult(
        SESSION_STATUS_NEEDS_USER_INPUT,
        PAUSE_REASON_NO_PROGRESS,
        question
    );
};

const handlePartialCompletionPause = (session) => {
    markPartialCompletionAndPause(session);

    return createCycleResult(
        SESSION_STATUS_NEEDS_USER_INPUT,
        PAUSE_REASON_PARTIAL_COMPLETION,
        session.pendingQuestion
    );
};

const shouldPauseForPartialCompletion = (session) => {
    const progress = summarizeTaskProgress(session.currentTask);
    const hasCompleted = progress.completed.length > 0;
    const hasPending = progress.pending.length > 0;
    return hasCompleted && hasPending;
};

const isBuildSuccessForCurrentMode = (sessionMode, buildResult, writesApplied) => {
    const buildPassed = buildResult.ok === true;
    const requiresWrite = sessionMode === MODE_TEST;
    const wroteSomething = writesApplied > 0;

    return buildPassed && (!requiresWrite || wroteSomething);
};

const isBuildPassedButNoArtifactWritten = (sessionMode, buildResult, writesApplied) => {
    const buildPassed = buildResult.ok === true;
    const requiresWrite = sessionMode === MODE_TEST;
    const wroteSomething = writesApplied > 0;

    return buildPassed && requiresWrite && !wroteSomething;
};

const updateNoProgressCounter = (session, loopResult, buildResult) => {
    const writesApplied = loopResult?.writesApplied ?? 0;
    const noProgress = writesApplied === 0 && buildResult.ok !== true;

    if (noProgress) {
        session.consecutiveNoProgressCycles += 1;
    } else {
        session.consecutiveNoProgressCycles = 0;
    }
};

const shouldPauseForNoProgress = (session) => {
    return session.consecutiveNoProgressCycles >= MAX_NO_PROGRESS_CYCLES;
};

const handleSuccessfulExecutionCycle = (session) => {
    markTaskCompletedFromBuild(session);
    session.consecutiveNoProgressCycles = 0;

    if (session.interactive) {
        return returnTaskCompletionToInspection(session);
    }

    return markSessionCompleted(session, "build_passed_and_artifact_written");
};

const handleBuildPassedButNoWrite = (session) => {
    session.consecutiveNoProgressCycles += 1;

    if (shouldPauseForPartialCompletion(session)) {
        return handlePartialCompletionPause(session);
    }

    session.input.push({
        role: ROLE_USER,
        content:
            "Build passed but you did not create or modify any tests. " +
            "You MUST write at least one JUnit test file for the requested class now. " +
            "Proceed to create src/test/java/... with meaningful assertions."
    });

    if (shouldPauseForNoProgress(session)) {
        return handleNoArtifactWrittenPause(session);
    }

    session.status = SESSION_STATUS_RUNNING;
    session.pendingQuestion = null;

    return createCycleResult(SESSION_STATUS_RUNNING, "retry_after_no_write", null);
};

const handleFailedBuildCycle = (session, loopResult, buildResult) => {
    pushBuildResult(session.input, `Build/test output (phase=${buildResult.phase})`, buildResult);

    updateNoProgressCounter(session, loopResult, buildResult);

    if (shouldPauseForNoProgress(session)) {
        return handleNoProgressPause(session);
    }

    session.status = SESSION_STATUS_RUNNING;
    session.pendingQuestion = null;

    return createCycleResult(SESSION_STATUS_RUNNING, "continue", null);
};

const runAgentCycle = async (session, logger) => {
    const maxToolTurnsPerLoop = MAX_TOOL_TURNS;

    if (session.phase === SESSION_PHASE_TASK_PREPARATION) {
        prepareTaskExecution(session);
    }

    session.iteration += 1;

    logger.log("========================================");
    logger.log(`Session iteration ${session.iteration}`);
    logger.log(`Phase: ${session.phase}`);
    logger.log("========================================");

    const activeToolsAll = toolsForMode(session.mode, tools);
    const buildToolNames = new Set([TOOL_COMPILE_PROD, TOOL_COMPILE_TESTS, TOOL_RUN_TESTS]);
    const activeTools = activeToolsAll.filter((toolDef) => !buildToolNames.has(toolDef.function?.name));

    const inspectionMode = isInspectionMode(session.mode);
    const writeMode = isWriteMode(session.mode);
    const toolsetForMode = writeMode ? activeTools : activeToolsAll;

    const loopResult = await runToolLoop({
        session,
        toolset: toolsetForMode,
        toolChoice: "auto",
        logger,
        callLocalTool,
        printToolResult,
        maxTurns: maxToolTurnsPerLoop
    });

    session.lastLoopResult = loopResult;
    logger.log(`Tool loop writesApplied=${loopResult.writesApplied ?? 0}`);

    const clarificationCheck = assistantSeemsToNeedHelp(session.input);
    if (clarificationCheck.needsHelp) {
        return handleAssistantClarificationPause(session, clarificationCheck.question);
    }

    if (inspectionMode) {
        return handleInspectionModeCycleCompletion(session);
    }

    updateWorkItemsFromEvidence(session);

    const build = await runBuildAndTests(session.mode, logger);
    session.lastBuildResult = build;

    const writesApplied = loopResult?.writesApplied ?? 0;

    if (isBuildSuccessForCurrentMode(session.mode, build, writesApplied)) {
        return handleSuccessfulExecutionCycle(session);
    }

    if (isBuildPassedButNoArtifactWritten(session.mode, build, writesApplied)) {
        return handleBuildPassedButNoWrite(session);
    }

    return handleFailedBuildCycle(session, loopResult, build);
};

const runInteractiveSession = async (session, logger) => {
    const rl = createConsoleInterface();
    let finished = false;

    try {
        while (!finished) {
            const cycleResult = await runAgentCycle(session, logger);

            if (cycleResult.status === SESSION_STATUS_RUNNING) {
                // keep going automatically
            } else if (cycleResult.status === SESSION_STATUS_COMPLETED) {
                logger.log("Session completed.");
                finished = true;
            } else if (cycleResult.status === SESSION_STATUS_NEEDS_USER_INPUT) {
                let resolved = false;

                while (!resolved) {
                    const userAction = await promptUserForSessionInput(session, logger, rl);

                    if (userAction.action === "retry_prompt") {
                        // stay in prompt loop
                    } else if (userAction.action === "ask_why") {
                        await askWhyInSession(session, logger);
                    } else if (userAction.action === "ask_assistant") {
                        await askAssistantInSession(session, logger, userAction.content);
                    } else if (userAction.action === "start_task") {
                        const taskText = typeof userAction.content === "string" ? userAction.content.trim() : "";

                        if (taskText.length > 0) {
                            resetExecutionStateForNewTask(session, taskText);
                            logger.log(`Session task updated. New mode: ${session.mode}`);
                            resolved = true;
                        }
                    } else if (userAction.action === "stop") {
                        session.phase = SESSION_PHASE_FAILED;
                        session.status = SESSION_STATUS_FAILED;
                        logger.log("Session stopped by user.");
                        resolved = true;
                        finished = true;
                    } else if (userAction.action === "continue") {
                        session.pendingQuestion = null;
                        session.status = SESSION_STATUS_RUNNING;
                        if (isInspectionMode(session.mode)) {
                            session.phase = SESSION_PHASE_INTERACTIVE_INSPECTION;
                        } else {
                            session.phase = SESSION_PHASE_AUTONOMOUS_EXECUTION;
                        }
                        resolved = true;
                    } else if (userAction.action === "append_user_message") {
                        const content = typeof userAction.content === "string" ? userAction.content.trim() : "";
                        if (content.length > 0) {
                            session.input.push({
                                role: ROLE_USER,
                                content
                            });
                        }
                        session.pendingQuestion = null;
                        session.status = SESSION_STATUS_RUNNING;
                        session.phase = isInspectionMode(session.mode)
                            ? SESSION_PHASE_INTERACTIVE_INSPECTION
                            : SESSION_PHASE_AUTONOMOUS_EXECUTION;
                        session.consecutiveNoProgressCycles = 0;
                        resolved = true;
                    }
                }
            } else {
                logger.log(`Session ended with status=${cycleResult.status}`);
                finished = true;
            }
        }
    } finally {
        rl.close();
    }
};

const runBatchSession = async (session, logger) => {
    let finished = false;
    const maxBatchCycles = 30;
    let cycleCount = 0;

    while (!finished && cycleCount < maxBatchCycles) {
        cycleCount += 1;
        const cycleResult = await runAgentCycle(session, logger);

        if (cycleResult.status === SESSION_STATUS_RUNNING) {
            // continue
        } else {
            finished = true;
        }
    }

    if (!finished) {
        logger.log(`Stopped after ${maxBatchCycles} session cycles.`);
    }
};

const ensureDirExists = (dirRel) => {
    const dirAbs = path.resolve(REPO_ROOT, dirRel);
    if (!fs.existsSync(dirAbs)) {
        fs.mkdirSync(dirAbs, {recursive: true});
    }
    return dirAbs;
};

const buildLogFilePath = (argv) => {
    const logArgPrefix = "--log=";
    let logRel = null;

    for (let i = 0; i < argv.length; i += 1) {
        const token = argv[i];
        if (typeof token === "string" && token.startsWith(logArgPrefix)) {
            logRel = token.slice(logArgPrefix.length).trim();
        }
    }

    const dirAbs = ensureDirExists(LOG_DIR);
    const fileName = (logRel && logRel.length > 0) ? logRel : DEFAULT_LOG_FILE;
    return path.resolve(dirAbs, fileName);
};

const createLogger = (logFileAbs) => {
    const stream = fs.createWriteStream(logFileAbs, {flags: "a", encoding: "utf8"});

    const log = (...args) => {
        const text = args.map((a) => (typeof a === "string" ? a : JSON.stringify(a, null, 2))).join(" ");
        const ts = new Date().toISOString();
        const formatted = `[${ts}] ${text}`;

        console.log(formatted);
        stream.write(formatted + "\n");
    };

    const error = (...args) => {
        const text = args.map((a) => (typeof a === "string" ? a : JSON.stringify(a, null, 2))).join(" ");
        const ts = new Date().toISOString();
        const formatted = `[${ts}] ${text}`;

        console.error(formatted);
        stream.write(formatted + "\n");
    };

    const close = () => {
        stream.end();
    };

    return {log, error, close, logFileAbs};
};

const TOOL_BLOCK_WIDTH = 4;

const padNum = (n) => {
    const text = String(n);
    return text.padStart(TOOL_BLOCK_WIDTH, "0");
};

const logBlockHeader = (logger, blockId, title) => {
    const id = padNum(blockId);
    logger.log(`===== [${id}] ${title} =====`);
};

const logBlockFooter = (logger, blockId) => {
    const id = padNum(blockId);
    logger.log(`===== [${id}] END =====`);
};

const openai = new OpenAI({apiKey: process.env.OPENAI_API_KEY});

const buildClassPath = () => {
    const pathSeparator = path.delimiter;

    const prodAbs = path.resolve(REPO_ROOT, PROD_OUTPUT_DIR);
    const testAbs = path.resolve(REPO_ROOT, TEST_OUTPUT_DIR);
    const libAbs = path.resolve(REPO_ROOT, LIB_DIR);
    const javafxLibAbs = path.resolve(REPO_ROOT, JAVAFX_LIB_DIR);

    const parts = [
        prodAbs,
        testAbs,
        `${libAbs}/*`,
        `${javafxLibAbs}/*`,
    ];

    return parts.join(pathSeparator);
};

const normalizeRelPath = (p) => p.replaceAll("\\", "/");

const isAbsoluteLike = (p) => path.isAbsolute(p);

const assertRepoRelative = (p) => {
    if (isAbsoluteLike(p)) {
        throw new Error(`Path must be repo-relative, got absolute path: ${p}`);
    }
    const normalized = normalizeRelPath(p);
    if (normalized.startsWith("../") || normalized.includes("/../")) {
        throw new Error(`Path must not escape repo root: ${p}`);
    }
};

const isWriteAllowed = (mode, relativePath) => {
    const normalized = normalizeRelPath(relativePath);

    const allowed =
        mode === MODE_REFACTOR
            ? WRITE_ALLOWED_PREFIXES_REFACTOR
            : WRITE_ALLOWED_PREFIXES_TEST;

    return allowed.some((prefix) => normalized.startsWith(prefix));
};

const buildProdCompileClassPath = () => {
    const pathSeparator = path.delimiter;

    const prodAbs = path.resolve(REPO_ROOT, PROD_OUTPUT_DIR);
    const libAbs = path.resolve(REPO_ROOT, LIB_DIR);
    const javafxLibAbs = path.resolve(REPO_ROOT, JAVAFX_LIB_DIR);

    const parts = [
        prodAbs,
        `${libAbs}/*`,
        `${javafxLibAbs}/*`,
    ];

    return parts.join(pathSeparator);
};

const normalizeAndValidatePath = (inputPath) => {
    const resolved = path.resolve(ALLOWED_ROOT, inputPath);
    const isInside = resolved === ALLOWED_ROOT || resolved.startsWith(ALLOWED_ROOT + path.sep);
    if (!isInside) {
        throw new Error(`Path not allowed: ${inputPath}`);
    }
    return resolved;
};

const safeReadFile = (filePath) => {
    const resolved = normalizeAndValidatePath(filePath);
    const stat = fs.statSync(resolved);
    if (!stat.isFile()) {
        throw new Error(`Not a file: ${filePath}`);
    }
    if (stat.size > MAX_FILE_BYTES) {
        throw new Error(`File too large (${stat.size} bytes). Max is ${MAX_FILE_BYTES}.`);
    }
    return fs.readFileSync(resolved, "utf-8");
};

const safeWriteFile = (mode, filePath, content) => {
    assertRepoRelative(filePath);
    const resolved = normalizeAndValidatePath(filePath);

    const isAllowed = isWriteAllowed(mode, filePath);
    if (!isAllowed) {
        throw new Error(`Write not allowed in ${mode} mode: ${filePath}`);
    }

    const byteLength = Buffer.byteLength(content, "utf-8");
    if (byteLength > MAX_WRITE_BYTES) {
        throw new Error(`Write too large (${byteLength} bytes). Max is ${MAX_WRITE_BYTES}.`);
    }

    fs.mkdirSync(path.dirname(resolved), {recursive: true});
    fs.writeFileSync(resolved, content, "utf-8");

    return {path: filePath, bytes_written: byteLength};
};

const assertWriteAllowed = (mode, relativePath) => {
    assertRepoRelative(relativePath);

    const isAllowed = isWriteAllowed(mode, relativePath);
    if (!isAllowed) {
        throw new Error(`Write not allowed in ${mode} mode: ${relativePath}`);
    }
};

const listFilesRecursive = (dirPath, maxCount) => {
    const resolvedDir = normalizeAndValidatePath(dirPath);
    const results = [];
    const stack = [resolvedDir];

    while (stack.length > 0 && results.length < maxCount) {
        const current = stack.pop();
        const entries = fs.readdirSync(current, {withFileTypes: true});

        for (const entry of entries) {
            const full = path.join(current, entry.name);

            // Basic ignore patterns (extend later)
            const name = entry.name;
            const isIgnored =
                name === ".git" ||
                name === "node_modules" ||
                name === "build" ||
                name === "target" ||
                name === ".gradle" ||
                name === ".idea";

            if (!isIgnored) {
                if (entry.isDirectory()) {
                    stack.push(full);
                } else if (entry.isFile()) {
                    const rel = path.relative(REPO_ROOT, full);
                    results.push(rel);
                }
            }

            if (results.length >= maxCount) {
                // no break (per your standard): use guard in loop conditions
            }
        }
    }

    return results;
};

const listFilesRecursiveFromAbsoluteRoot = (absoluteRoot, maxCount) => {
    const results = [];
    const stack = [absoluteRoot];

    while (stack.length > 0 && results.length < maxCount) {
        const current = stack.pop();
        const entries = fs.readdirSync(current, {withFileTypes: true});

        for (const entry of entries) {
            const full = path.join(current, entry.name);

            if (entry.isDirectory()) {
                stack.push(full);
            } else if (entry.isFile()) {
                results.push(full);
            }
        }
    }

    return results;
};

const searchRepoRipgrep = async (query, maxResults, isRegex) => {
    const rgArgs = [
        "--no-heading",
        "--line-number",
        "--column",
        "--hidden",
        "--glob", "!.git/*",
        "--glob", "!node_modules/*",
        "--glob", "!target/*",
        "--glob", "!build/*",
        "--glob", "!out/*",
        "--glob", "!out/**",
        "--max-count",
        String(maxResults),
    ];

    const useRegex = isRegex === true;
    if (!useRegex) {
        rgArgs.push("--fixed-strings");
    }

    rgArgs.push("--", query, ".");

    try {
        const {stdout} = await execFileAsync("rg", rgArgs, {cwd: REPO_ROOT, maxBuffer: MAX_PROCESS_BUFFER_BYTES});
        const lines = stdout.split("\n").filter((l) => l.trim().length > 0);

        return lines.map((line) => {
            const first = line.indexOf(":");
            const second = line.indexOf(":", first + 1);
            const third = line.indexOf(":", second + 1);

            const file = line.slice(0, first);
            const lineNum = Number(line.slice(first + 1, second));
            const colNum = Number(line.slice(second + 1, third));
            const text = line.slice(third + 1);

            return {file, line: lineNum, column: colNum, text};
        });
    } catch (err) {
        const hasNoMatches = typeof err?.code === "number" && err.code === 1;
        if (hasNoMatches) {
            return [];
        }
        throw err;
    }
};

const listJarFiles = (absoluteDir) => {
    const exists = fs.existsSync(absoluteDir);
    if (!exists) {
        return [];
    }

    const entries = fs.readdirSync(absoluteDir, {withFileTypes: true});
    return entries
        .filter((e) => e.isFile() && e.name.endsWith(".jar"))
        .map((e) => path.join(absoluteDir, e.name));
};

const buildResolvedRuntimeClassPath = () => {
    const pathSeparator = path.delimiter;

    const prodAbs = path.resolve(REPO_ROOT, PROD_OUTPUT_DIR);
    const testAbs = path.resolve(REPO_ROOT, TEST_OUTPUT_DIR);
    const libAbs = path.resolve(REPO_ROOT, LIB_DIR);
    const javafxLibAbs = path.resolve(REPO_ROOT, JAVAFX_LIB_DIR);

    const libJars = listJarFiles(libAbs);
    const javafxJars = listJarFiles(javafxLibAbs);

    const parts = [
        prodAbs,
        testAbs,
        ...libJars,
        ...javafxJars,
    ];

    return parts.join(pathSeparator);
};

const ensureSingleFileDiff = (diffText) => {
    const plusPlusCount = diffText.split("\n").filter(l => l.startsWith("+++ ")).length;
    if (plusPlusCount > 1) {
        throw new Error("apply_patch only supports single-file diffs.");
    }
};

const applyUnifiedDiffToText = (originalText, diffText) => {
    const originalLines = originalText.split("\n");
    const diffLines = diffText.split("\n");

    const outputLines = [];
    let originalIndex = 0;

    const HUNK_HEADER_PREFIX = "@@";
    const DIFF_FILE_PREFIX_1 = "---";
    const DIFF_FILE_PREFIX_2 = "+++";
    const SPACE_PREFIX = " ";
    const ADD_PREFIX = "+";
    const REMOVE_PREFIX = "-";

    const parseHunkHeader = (line) => {
        // @@ -start,count +start,count @@
        const header = line.trim();
        const firstSpace = header.indexOf(" ");
        const secondSpace = header.indexOf(" ", firstSpace + 1);

        const left = header.slice(firstSpace + 1, secondSpace);   // -a,b
        const rightStart = header.indexOf("+", secondSpace);
        const rightEnd = header.indexOf(" @@", rightStart);
        const right = header.slice(rightStart, rightEnd);         // +c,d

        const leftParts = left.slice(1).split(",");
        const rightParts = right.slice(1).split(",");

        const leftStart = Number(leftParts[0]);
        const leftCount = leftParts.length > 1 ? Number(leftParts[1]) : 1;
        const rightStartNum = Number(rightParts[0]);
        const rightCount = rightParts.length > 1 ? Number(rightParts[1]) : 1;

        if (!Number.isFinite(leftStart) || !Number.isFinite(leftCount) || !Number.isFinite(rightStartNum) || !Number.isFinite(rightCount)) {
            throw new Error(`Invalid hunk header: ${line}`);
        }

        return {
            leftStart,
            leftCount,
            rightStart: rightStartNum,
            rightCount
        };
    };

    let i = 0;
    while (i < diffLines.length) {
        const line = diffLines[i];

        const isFileHeader1 = line.startsWith(DIFF_FILE_PREFIX_1);
        const isFileHeader2 = line.startsWith(DIFF_FILE_PREFIX_2);

        if (isFileHeader1 || isFileHeader2) {
            i += 1;
        } else if (line.startsWith(HUNK_HEADER_PREFIX)) {
            const hunk = parseHunkHeader(line);

            // Copy unchanged lines before hunk (leftStart is 1-based)
            const targetOriginalIndex = hunk.leftStart - 1;
            while (originalIndex < targetOriginalIndex) {
                outputLines.push(originalLines[originalIndex]);
                originalIndex += 1;
            }

            i += 1;

            // Apply hunk lines
            while (i < diffLines.length) {
                const hunkLine = diffLines[i];

                const isNextHunk = hunkLine.startsWith(HUNK_HEADER_PREFIX);
                const isEndOfFile = hunkLine.startsWith(DIFF_FILE_PREFIX_1) || hunkLine.startsWith(DIFF_FILE_PREFIX_2);

                if (isNextHunk || isEndOfFile) {
                    break;
                }

                const prefix = hunkLine.slice(0, 1);
                const content = hunkLine.slice(1);

                const isContext = prefix === SPACE_PREFIX;
                const isAdd = prefix === ADD_PREFIX;
                const isRemove = prefix === REMOVE_PREFIX;
                const isNoNewlineMarker = hunkLine.startsWith("\\ No newline at end of file");

                if (isNoNewlineMarker) {
                    i += 1;
                } else if (isContext) {
                    const originalLine = originalLines[originalIndex];
                    if (originalLine !== content) {
                        throw new Error(`Patch context mismatch at line ${originalIndex + 1}. Expected: "${content}" Got: "${originalLine ?? ""}"`);
                    }
                    outputLines.push(originalLine);
                    originalIndex += 1;
                    i += 1;
                } else if (isRemove) {
                    const originalLine = originalLines[originalIndex];
                    if (originalLine !== content) {
                        throw new Error(`Patch removal mismatch at line ${originalIndex + 1}. Expected: "${content}" Got: "${originalLine ?? ""}"`);
                    }
                    originalIndex += 1;
                    i += 1;
                } else if (isAdd) {
                    outputLines.push(content);
                    i += 1;
                } else {
                    throw new Error(`Unsupported patch line: ${hunkLine}`);
                }
            }
        } else if (line.trim().length === 0) {
            // allow trailing empty lines in diff
            i += 1;
        } else {
            throw new Error(`Unexpected diff content: ${line}`);
        }
    }

    // Copy the rest of the original
    while (originalIndex < originalLines.length) {
        outputLines.push(originalLines[originalIndex]);
        originalIndex += 1;
    }

    return outputLines.join("\n");
};

const parseMode = (rawPrompt) => {
    const trimmed = rawPrompt.trimStart();
    const match = trimmed.match(/^mode=([a-zA-Z]+)/i);

    if (!match) {
        const lower = trimmed.toLowerCase();
        const looksLikeTestRequest =
            lower.includes("write tests") ||
            lower.includes("generate tests") ||
            lower.includes("create tests") ||
            lower.includes("derive tests") ||
            lower.includes("unit tests");
        if (looksLikeTestRequest) {
            return MODE_TEST;
        }
        return DEFAULT_MODE;
    }

    const mode = match[1].toLowerCase();

    const isKnown =
        mode === MODE_EXPLAIN ||
        mode === MODE_PLAN ||
        mode === MODE_DEBUG ||
        mode === MODE_TEST ||
        mode === MODE_REFACTOR;

    return isKnown ? mode : DEFAULT_MODE;
};

const extractTargetsFromPrompt = (promptText) => {
    const text = String(promptText ?? "");
    const matches = text.match(/[a-z][a-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+/g) ?? [];

    const blockedPrefixes = [
        "java.",
        "javax.",
        "jakarta.",
        "org.junit.",
        "org.assertj.",
        "org.mockito.",
        "com.fasterxml.",
        "com.google."
    ];

    const unique = new Set();
    for (let i = 0; i < matches.length; i += 1) {
        const m = matches[i];
        let blocked = false;
        for (let j = 0; j < blockedPrefixes.length; j += 1) {
            const p = blockedPrefixes[j];
            if (m.startsWith(p)) {
                blocked = true;
            }
        }
        if (!blocked) {
            unique.add(m);
        }
    }
    return Array.from(unique);
};

const stripModePrefix = (rawPrompt) => {
    const trimmed = rawPrompt.trimStart();
    const match = trimmed.match(/^mode=[a-zA-Z]+\s*/i);
    if (!match) {
        return rawPrompt;
    }
    return trimmed.slice(match[0].length).trim();
};

const tools = [
    {
        type: "function",
        function: {
            name: "list_files",
            description: "List files under a directory relative to repo root (read-only).",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {
                    dir: {type: "string", description: "Directory path relative to repo root, e.g. '.' or 'src'."},
                    max_count: {type: "integer", description: "Maximum number of files to return."},
                },
                required: ["dir"],
            },
        },
    },
    {
        type: "function",
        function: {
            name: "read_file",
            description: "Read a UTF-8 text file from the repo (read-only).",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {
                    path: {type: "string", description: "File path relative to repo root."},
                },
                required: ["path"],
            },
        },
    },
    {
        type: "function",
        function: {
            name: "search_repo",
            description: "Search the repo using ripgrep and return matching lines with file/line/column.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {
                    query: {
                        type: "string",
                        description: "Search query (plain text by default; regex if is_regex=true)."
                    },
                    max_results: {type: "integer", description: "Maximum number of matches to return."},
                    is_regex: {
                        type: "boolean",
                        description: "If true, treat query as regex. Default false (fixed-string search)."
                    }
                },
                required: ["query"],
            },
        },
    },
    {
        type: "function",
        function: {
            name: "compile_prod",
            description: "Compile Java production sources under src/main/java into the production output directory.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {},
            },
        },
    },
    {
        type: "function",
        function: {
            name: "compile_tests",
            description: "Compile Java test sources into the test output directory.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {},
            },
        },
    },
    {
        type: "function",
        function: {
            name: "run_tests",
            description: "Run JUnit tests using the JUnit console launcher.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {},
            },
        },
    },
    {
        type: "function",
        function: {
            name: "write_file",
            description: "Create or overwrite a UTF-8 text file within allowed write paths.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {
                    path: {type: "string", description: "File path relative to repo root."},
                    content: {type: "string", description: "Full file contents (UTF-8)."}
                },
                required: ["path", "content"]
            }
        }
    },
    {
        type: "function",
        function: {
            name: "apply_patch",
            description: "Apply a unified diff patch to a single file under src/test/java or src/test/resources.",
            parameters: {
                type: "object",
                additionalProperties: false,
                properties: {
                    path: {type: "string", description: "Target file path relative to repo root."},
                    patch: {type: "string", description: "Unified diff content (single-file patch)."}
                },
                required: ["path", "patch"]
            }
        }
    },
];

const toolsForMode = (mode, allTools) => {
    const allowedNames = [];

    if (mode === MODE_EXPLAIN || mode === MODE_PLAN) {
        allowedNames.push(TOOL_LIST_FILES, TOOL_READ_FILE, TOOL_SEARCH_REPO);
    } else if (mode === MODE_DEBUG) {
        allowedNames.push(
            TOOL_LIST_FILES,
            TOOL_READ_FILE,
            TOOL_SEARCH_REPO,
            TOOL_COMPILE_PROD,
            TOOL_COMPILE_TESTS,
            TOOL_RUN_TESTS
        );
    } else if (mode === MODE_TEST) {
        allowedNames.push(
            TOOL_LIST_FILES,
            TOOL_READ_FILE,
            TOOL_SEARCH_REPO,
            TOOL_COMPILE_PROD,
            TOOL_COMPILE_TESTS,
            TOOL_RUN_TESTS,
            TOOL_WRITE_FILE,
            TOOL_APPLY_PATCH
        );
    } else if (mode === MODE_REFACTOR) {
        allowedNames.push(
            TOOL_LIST_FILES,
            TOOL_READ_FILE,
            TOOL_SEARCH_REPO,
            TOOL_COMPILE_PROD,
            TOOL_COMPILE_TESTS,
            TOOL_RUN_TESTS,
            TOOL_WRITE_FILE,
            TOOL_APPLY_PATCH
        );
    } else {
        allowedNames.push(TOOL_LIST_FILES, TOOL_READ_FILE, TOOL_SEARCH_REPO);
    }
    return allTools.filter((t) => allowedNames.includes(t.function?.name));
};

const handleReadFileTool = async (args) => {
    const content = safeReadFile(args.path);
    return {path: args.path, content};
};

const handleListFilesTool = async (args) => {
    const maxCount = Number.isInteger(args.max_count) ? args.max_count : MAX_SEARCH_RESULTS;
    const files = listFilesRecursive(args.dir, maxCount);
    return {dir: args.dir, files};
};

const handleSearchRepoTool = async (args) => {
    const maxResults = Number.isInteger(args.max_results) ? args.max_results : MAX_SEARCH_RESULTS;
    const isRegex = args.is_regex === true;
    const matches = await searchRepoRipgrep(args.query, maxResults, isRegex);
    return {query: args.query, is_regex: isRegex, matches};
};

const handleCompileTestsTool = async () => {
    const testSourceRoot = path.resolve(REPO_ROOT, "src/test/java");
    const outDirAbs = path.resolve(REPO_ROOT, TEST_OUTPUT_DIR);

    if (!fs.existsSync(testSourceRoot)) {
        return {status: "no_tests_dir", message: "src/test/java does not exist."};
    }

    fs.mkdirSync(outDirAbs, {recursive: true});

    const allAbsFiles = listFilesRecursiveFromAbsoluteRoot(testSourceRoot, MAX_JAVA_FILES);
    const javaAbsFiles = allAbsFiles.filter((filePath) => filePath.endsWith(".java"));

    if (javaAbsFiles.length === 0) {
        return {status: "no_test_files", message: "No .java files under src/test/java."};
    }

    const javaFilesRel = javaAbsFiles.map((absPath) => {
        const rel = path.relative(testSourceRoot, absPath);
        const javaPrefix = `java${path.sep}`;
        return rel.startsWith(javaPrefix) ? rel.slice(javaPrefix.length) : rel;
    });

    const classPath = buildClassPath();
    const javacArgs = [
        "-cp",
        classPath,
        "-d",
        outDirAbs,
        ...javaFilesRel
    ];

    const command = ["javac", ...javacArgs];
    const cwd = testSourceRoot;

    const {stdout, stderr} = await execFileAsync(command[0], command.slice(1), {
        cwd,
        maxBuffer: MAX_PROCESS_BUFFER_BYTES
    });

    return {
        status: "compiled",
        cwd,
        command,
        files_compiled: javaFilesRel.length,
        stdout,
        stderr
    };
};

const handleRunTestsTool = async () => {
    const testAbs = path.resolve(REPO_ROOT, TEST_OUTPUT_DIR);
    const classPath = buildResolvedRuntimeClassPath();

    const javaArgs = [
        "-jar", JUNIT_JAR,
        "execute",
        "--class-path",
        classPath,
        "--scan-classpath",
        testAbs
    ];

    const command = ["java", ...javaArgs];
    const cwd = REPO_ROOT;

    try {
        const {stdout, stderr} = await execFileAsync(command[0], command.slice(1), {
            cwd,
            maxBuffer: MAX_PROCESS_BUFFER_BYTES
        });

        return {status: "ran", exit_code: 0, cwd, command, stdout, stderr};
    } catch (err) {
        const stdout = typeof err?.stdout === "string" ? err.stdout : String(err?.stdout ?? "");
        const stderr = typeof err?.stderr === "string" ? err.stderr : String(err?.stderr ?? "");
        const code = typeof err?.code === "number" ? err.code : 1;

        return {status: "ran", exit_code: code, cwd, command, stdout, stderr};
    }
};

const handleCompileProdTool = async () => {
    const prodSourceDir = "src/main/java";
    const prodRootAbs = path.resolve(REPO_ROOT, prodSourceDir);
    const outDirAbs = path.resolve(REPO_ROOT, PROD_OUTPUT_DIR);

    if (!fs.existsSync(prodRootAbs)) {
        return {status: "no_prod_dir", message: "src/main/java does not exist."};
    }

    fs.mkdirSync(outDirAbs, {recursive: true});

    const allAbsFiles = listFilesRecursiveFromAbsoluteRoot(prodRootAbs, MAX_JAVA_FILES);
    const javaAbsFiles = allAbsFiles.filter((filePath) => filePath.endsWith(".java"));

    if (javaAbsFiles.length === 0) {
        return {status: "no_prod_files", message: "No .java files under src/main/java."};
    }

    const javaFilesRelToProdRoot = javaAbsFiles.map((filePath) => path.relative(prodRootAbs, filePath));
    const classPath = buildProdCompileClassPath();

    const javacArgs = [
        "-cp",
        classPath,
        "-d",
        outDirAbs,
        ...javaFilesRelToProdRoot
    ];

    const command = ["javac", ...javacArgs];
    const cwd = prodRootAbs;

    const {stdout, stderr} = await execFileAsync(command[0], command.slice(1), {
        cwd,
        maxBuffer: MAX_PROCESS_BUFFER_BYTES
    });

    return {
        status: "compiled",
        cwd,
        command,
        files_compiled: javaFilesRelToProdRoot.length,
        stdout,
        stderr
    };
};

const handleWriteFileTool = async (mode, args) => {
    const result = safeWriteFile(mode, args.path, args.content);
    return {status: "written", ...result};
};

const handleApplyPatchTool = async (mode, args) => {
    const byteLength = Buffer.byteLength(args.patch, "utf-8");
    if (byteLength > MAX_PATCH_BYTES) {
        throw new Error(`Patch too large (${byteLength} bytes). Max is ${MAX_PATCH_BYTES}.`);
    }

    assertWriteAllowed(mode, args.path);

    const resolved = normalizeAndValidatePath(args.path);
    const original = fs.existsSync(resolved) ? fs.readFileSync(resolved, "utf-8") : "";

    ensureSingleFileDiff(args.patch);
    const updated = applyUnifiedDiffToText(original, args.patch);

    fs.mkdirSync(path.dirname(resolved), {recursive: true});
    fs.writeFileSync(resolved, updated, "utf-8");

    return {
        status: "patched",
        path: args.path,
        bytes_written: Buffer.byteLength(updated, "utf-8")
    };
};

const callLocalTool = async (mode, toolName, args) => {
    if (toolName === TOOL_READ_FILE) {
        return handleReadFileTool(args);
    }

    if (toolName === TOOL_LIST_FILES) {
        return handleListFilesTool(args);
    }

    if (toolName === TOOL_SEARCH_REPO) {
        return handleSearchRepoTool(args);
    }

    if (toolName === TOOL_COMPILE_TESTS) {
        return handleCompileTestsTool();
    }

    if (toolName === TOOL_RUN_TESTS) {
        return handleRunTestsTool();
    }

    if (toolName === TOOL_COMPILE_PROD) {
        return handleCompileProdTool();
    }

    if (toolName === TOOL_WRITE_FILE) {
        return handleWriteFileTool(mode, args);
    }

    if (toolName === TOOL_APPLY_PATCH) {
        return handleApplyPatchTool(mode, args);
    }

    throw new Error(`Unknown tool: ${toolName}`);
};

const buildSystemInstructions = (mode) => {
    const base = [
        "You are a senior software engineer assisting with a Java repository.",
        "You MUST ground every claim in the repo by using tools (search_repo/read_file) before you write code.",
        "If you cannot find a symbol (class/method/field) in the repo, you MUST NOT use it.",
        "Never guess method signatures. Verify them by reading the source file(s).",
        "When you propose or write tests, you MUST cite the exact file(s) you read and the method signature(s) you will test.",
    ];

    const readOnly = [
        "You cannot modify files in this mode.",
        "Prefer: search_repo then read_file.",
    ];

    const plan = [
        "You are in PLAN mode: produce an implementation plan without modifying files.",
        "You cannot compile or run tests in this mode.",
        "Required output sections: Understanding, Proposed change, Implementation steps (with files/classes), Test plan, Risks/open questions.",
        "Use tools to ground the plan: search_repo/list_files to locate entry points, then read_file to confirm control flow.",
    ];

    const debug = [
        "You may compile and run tests to reproduce issues.",
        "You cannot modify files in this mode.",
        "Prefer: compile_prod, compile_tests, run_tests when debugging test failures.",
    ];

    const test = [
        "You may create/modify test files only under src/test/java and src/test/resources.",
        "You MUST compile and run tests after writing: compile_prod, compile_tests, run_tests.",
        "Do not modify production source files in this mode.",
        "Hard rule: NO invented symbols. You must prove every referenced symbol exists by tool evidence.",
        "Workflow requirement: search_repo + read_file the exact target classes/methods BEFORE writing or patching any test file.",
        "If compilation fails, you MUST fix tests by re-reading the relevant production/test code and adjusting imports/types until compile passes.",
        "Do not create placeholder/marker files; every written test must target a real class/method verified by read_file.",
    ];

    const refactor = [
        "You may modify production and test files using apply_patch/write_file.",
        "Refactor goal: improve readability/structure without changing externally observable behavior.",
        "Do NOT change public interfaces/APIs (public/protected method signatures, public fields, class names, package names).",
        "Do NOT change file formats, CLI/UI behavior, persistence schemas, or outputs unless explicitly instructed.",
        "After changes, run compile_prod, compile_tests, then run_tests and report results.",
        "Hard rule: NO invented symbols. You must prove every referenced symbol exists by tool evidence.",
        "Before writing tests, locate signatures using search_repo + read_file.",
        "Do not create placeholder/marker files; every written test must target a real class/method verified by read_file.",
    ];

    if (mode === MODE_EXPLAIN) return base.concat(readOnly).join("\n");
    if (mode === MODE_PLAN) return base.concat(plan).join("\n");
    if (mode === MODE_DEBUG) return base.concat(debug).join("\n");
    if (mode === MODE_TEST) return base.concat(test).join("\n");
    if (mode === MODE_REFACTOR) return base.concat(refactor).join("\n");

    return base.concat(readOnly).join("\n");
};

const printWithLineNumbers = (text, logLine) => {
    const lines = text.split("\n");
    const width = String(lines.length).length;

    for (let i = 0; i < lines.length; i += 1) {
        const lineNumber = String(i + 1).padStart(width, " ");
        logLine(`${lineNumber} | ${lines[i]}`);
    }
};

const printToolResult = (toolName, result, logger) => {
    if (result === undefined || result === null) {
        logger.log(`[tool result] ${toolName}: <no result>`);
        return;
    }

    if (toolName === TOOL_READ_FILE) {
        const filePath = typeof result.path === "string" ? result.path : "<unknown>";
        const content = typeof result.content === "string" ? result.content : "";

        logger.log(`[tool result] ${toolName}: ${filePath}`);
        logger.log("----- FILE START -----");

        printWithLineNumbers(content, logger.log);

        logger.log("----- FILE END -----");
        return;
    }

    if (toolName === TOOL_SEARCH_REPO) {
        const matches = Array.isArray(result?.matches) ? result.matches : [];
        const query = typeof result?.query === "string" ? result.query : "";

        logger.log(`[tool result] ${toolName}: "${query}" (${matches.length} matches)`);

        for (let i = 0; i < matches.length; i += 1) {
            const m = matches[i];
            logger.log(`${m.file}:${m.line}:${m.column}: ${m.text}`);
        }

        return;
    }

    if (toolName === TOOL_RUN_TESTS) {
        const exitCodeText = typeof result?.exit_code === "number" ? String(result.exit_code) : "?";
        logger.log(`[tool result] run_tests exit_code=${exitCodeText}`);

        const stdout = typeof result?.stdout === "string" ? result.stdout : "";
        const stderr = typeof result?.stderr === "string" ? result.stderr : "";

        if (stdout.length > 0) {
            logger.log("----- TEST STDOUT -----");
            printWithLineNumbers(stdout, logger.log);
            logger.log("----- END TEST STDOUT -----");
        }

        if (stderr.length > 0) {
            logger.log("----- TEST STDERR -----");
            printWithLineNumbers(stderr, logger.log);
            logger.log("----- END TEST STDERR -----");
        }

        return;
    }

    const hasStdout = typeof result?.stdout === "string" && result.stdout.length > 0;
    const hasStderr = typeof result?.stderr === "string" && result.stderr.length > 0;

    if (hasStdout) {
        logger.log("----- STDOUT -----");
        printWithLineNumbers(result.stdout, logger.log);
        logger.log("----- END STDOUT -----");
    }

    if (hasStderr) {
        logger.log("----- STDERR -----");
        printWithLineNumbers(result.stderr, logger.log);
        logger.log("----- END STDERR -----");
    }

    const pretty = JSON.stringify(result, null, 2);
    logger.log(`[tool result] ${toolName}\n${pretty}`);
};

const extractImportedTypes = (javaText) => {
    const lines = String(javaText ?? "").split("\n");
    const results = new Set();

    const importPrefix = "import ";
    const staticPrefix = "import static ";

    for (let i = 0; i < lines.length; i += 1) {
        const line = lines[i].trim();
        const isImport = line.startsWith(importPrefix);
        const isStaticImport = line.startsWith(staticPrefix);

        if (isImport || isStaticImport) {
            const after = isStaticImport ? line.slice(staticPrefix.length) : line.slice(importPrefix.length);
            const semicolonIndex = after.indexOf(";");
            const imported = semicolonIndex >= 0 ? after.slice(0, semicolonIndex).trim() : after.trim();

            const isWildcard = imported.endsWith(".*");
            if (!isWildcard && imported.length > 0) {
                results.add(imported);
            }
        }
    }

    return Array.from(results);
};

const buildToolErrorResultFromException = (toolName, err) => {
    const message =
        typeof err?.message === "string" ? err.message : String(err ?? "Unknown error");

    const stdout =
        typeof err?.stdout === "string" ? err.stdout : String(err?.stdout ?? "");

    const stderr =
        typeof err?.stderr === "string" ? err.stderr : String(err?.stderr ?? "");

    return {
        status: "tool_error",
        tool: toolName,
        message,
        stdout,
        stderr
    };
};

const parseToolArgs = (rawArgs) => {
    try {
        return {
            parsedOk: true,
            args: JSON.parse(rawArgs)
        };
    } catch (err) {
        return {
            parsedOk: false,
            args: null
        };
    }
};

const createInvalidJsonToolResult = (toolName, rawArgs) => {
    return {
        status: "tool_error",
        tool: toolName,
        message: `Invalid JSON tool args: ${rawArgs}`
    };
};

const createDuplicateSkipResult = (toolName, rawArgs, duplicateCount) => {
    return {
        status: "skipped_duplicate",
        tool: toolName,
        args: rawArgs,
        message: `Skipped duplicate tool call (${duplicateCount} times).`
    };
};

const incrementDuplicateCount = (seenToolCallSignatures, toolName, rawArgs) => {
    const signature = `${toolName}::${rawArgs}`;
    const previousCount = seenToolCallSignatures.get(signature) ?? 0;
    const nextCount = previousCount + 1;
    seenToolCallSignatures.set(signature, nextCount);
    return nextCount;
};

const markEvidenceFromRead = (targetEvidence, args) => {
    const filePath = typeof args?.path === "string" ? normalizeRelPath(args.path) : "";
    if (filePath.length > 0) {
        targetEvidence.readFiles.add(filePath);
    }

    const targets = targetEvidence.targets;
    for (let i = 0; i < targets.length; i += 1) {
        const target = targets[i];
        const expectedJavaPathSuffix = `${target.replaceAll(".", "/")}.java`;
        if (filePath.endsWith(expectedJavaPathSuffix)) {
            targetEvidence.foundByRead.add(target);
        }
    }
};

const markEvidenceFromSearch = (targetEvidence, result) => {
    const matches = Array.isArray(result?.matches) ? result.matches : [];
    const targets = Array.isArray(targetEvidence?.targets) ? targetEvidence.targets : [];

    for (let i = 0; i < matches.length; i += 1) {
        const match = matches[i];
        const fileRaw = typeof match?.file === "string" ? match.file : "";
        const filePath = fileRaw.length > 0 ? normalizeRelPath(fileRaw) : "";

        if (filePath.length > 0) {
            for (let j = 0; j < targets.length; j += 1) {
                const target = targets[j];
                const expectedJavaPathSuffix = `${target.replaceAll(".", "/")}.java`;
                if (filePath.endsWith(expectedJavaPathSuffix)) {
                    targetEvidence.foundBySearch.add(target);
                }
            }
        }
    }
};

const getRootPackagePrefixes = (targets) => {
    const prefixes = new Set();
    const list = Array.isArray(targets) ? targets : [];

    for (let i = 0; i < list.length; i += 1) {
        const target = list[i];
        if (typeof target === "string") {
            const dotIndex = target.indexOf(".");
            if (dotIndex > 0) {
                prefixes.add(target.slice(0, dotIndex + 1));
            }
        }
    }

    return Array.from(prefixes);
};

const isExternalImport = (fqcn) => {
    const externalPrefixes = [
        "java.",
        "javax.",
        "jakarta.",
        "org.junit.",
        "org.assertj.",
        "org.mockito.",
        "com.google.",
        "com.fasterxml.",
        "kotlin.",
        "scala."
    ];

    for (let i = 0; i < externalPrefixes.length; i += 1) {
        const prefix = externalPrefixes[i];
        if (fqcn.startsWith(prefix)) {
            return true;
        }
    }

    return false;
};

const isRepoTypeByPrefix = (fqcn, rootPrefixes) => {
    if (typeof fqcn !== "string" || fqcn.length === 0) {
        return false;
    }

    if (isExternalImport(fqcn)) {
        return false;
    }

    for (let i = 0; i < rootPrefixes.length; i += 1) {
        const prefix = rootPrefixes[i];
        if (fqcn.startsWith(prefix)) {
            return true;
        }
    }

    return false;
};

const ensureTargetsFromWrite = (targetEvidence, toolName, args) => {
    if (toolName !== TOOL_WRITE_FILE) {
        return;
    }

    const content = typeof args?.content === "string" ? args.content : "";
    const imported = extractImportedTypes(content);
    const rootPrefixes = getRootPackagePrefixes(targetEvidence.targets);

    for (let i = 0; i < imported.length; i += 1) {
        const importedType = imported[i];
        const okToAdd = isRepoTypeByPrefix(importedType, rootPrefixes);

        if (okToAdd && !targetEvidence.targets.includes(importedType)) {
            targetEvidence.targets.push(importedType);
        }
    }
};

const hasRequiredEvidenceToWrite = (targetEvidence) => {
    const targets = targetEvidence.targets;
    const hasTargets = Array.isArray(targets) && targets.length > 0;

    if (!hasTargets) {
        const hasAnyRead = targetEvidence.readFiles.size > 0;
        const hasAnySearch = targetEvidence.foundBySearch.size > 0;
        return hasAnyRead || hasAnySearch;
    }

    for (let i = 0; i < targets.length; i += 1) {
        const target = targets[i];
        const found =
            targetEvidence.foundByRead.has(target) ||
            targetEvidence.foundBySearch.has(target);

        if (!found) {
            return false;
        }
    }

    return true;
};

const requiredEvidenceMissingMessage = (targetEvidence) => {
    const targets = targetEvidence.targets;
    const hasTargets = Array.isArray(targets) && targets.length > 0;

    if (!hasTargets) {
        return (
            "Write blocked: you must gather repo evidence BEFORE writing tests. " +
            "Use search_repo to locate the target class, then read_file to confirm method signatures."
        );
    }

    const missing = [];
    for (let i = 0; i < targets.length; i += 1) {
        const target = targets[i];
        const found =
            targetEvidence.foundByRead.has(target) ||
            targetEvidence.foundBySearch.has(target);

        if (!found) {
            missing.push(target);
        }
    }

    const list = missing.length > 0 ? missing.join(", ") : "<unknown>";
    return (
        "Write blocked: you must read_file the source for these targets BEFORE writing tests: " +
        list +
        ". Use search_repo to find the file, then read_file to confirm method signatures."
    );
};

const recordWriteInTaskState = (session, toolName, args) => {
    const hasTask = session.currentTask && typeof session.currentTask === "object";
    if (!hasTask) {
        return;
    }

    const filePath = typeof args?.path === "string" ? normalizeRelPath(args.path) : "";
    if (filePath.length === 0) {
        return;
    }

    const isPatch = toolName === TOOL_APPLY_PATCH;
    markTaskFileWrite(session, filePath, isPatch);

    const workItems = Array.isArray(session.currentTask.workItems)
        ? session.currentTask.workItems
        : [];

    for (let i = 0; i < workItems.length; i += 1) {
        const item = workItems[i];
        const itemTarget = typeof item?.target === "string" ? item.target : "";

        if (itemTarget === GENERAL_WORK_ITEM_TARGET) {
            if (isPatch) {
                appendUnique(item.patchedFiles, filePath);
            } else {
                appendUnique(item.writtenFiles, filePath);
            }

            if (item.status === WORK_ITEM_STATUS_PENDING) {
                item.status = WORK_ITEM_STATUS_RUNNING;
            }
        } else {
            const expectedJavaPathSuffix = `${itemTarget.replaceAll(".", "/")}.java`;
            const matchesTargetFile =
                session.targetEvidence.readFiles.has(filePath) ||
                filePath.endsWith(expectedJavaPathSuffix);

            if (matchesTargetFile) {
                if (isPatch) {
                    appendUnique(item.patchedFiles, filePath);
                } else {
                    appendUnique(item.writtenFiles, filePath);
                }

                if (item.status === WORK_ITEM_STATUS_PENDING) {
                    item.status = WORK_ITEM_STATUS_RUNNING;
                }
            }
        }
    }
};

const pushToolResultMessage = (input, callId, result) => {
    input.push({
        role: ROLE_TOOL,
        tool_call_id: callId,
        content: JSON.stringify(result)
    });
};

const handleToolExecutionSuccess = ({
                                        session,
                                        toolName,
                                        args,
                                        result,
                                        printToolResult,
                                        logger
                                    }) => {
    if (toolName === TOOL_WRITE_FILE || toolName === TOOL_APPLY_PATCH) {
        recordWriteInTaskState(session, toolName, args);
    }

    if (toolName === TOOL_READ_FILE) {
        markEvidenceFromRead(session.targetEvidence, args);
    }

    if (toolName === TOOL_SEARCH_REPO) {
        markEvidenceFromSearch(session.targetEvidence, result);
    }

    printToolResult(toolName, result, logger);
};

const createStopRepeatingMessage = () => {
    return {
        role: ROLE_USER,
        content:
            "Stop repeating the same tool call. Summarize what you have verified from the repo and state the next concrete step."
    };
};

const runToolLoop = async ({
                               session,
                               toolset,
                               toolChoice,
                               logger,
                               callLocalTool,
                               printToolResult,
                               maxTurns
                           }) => {
    const mode = session.mode;
    const input = session.input;
    const duplicateBudget = session.duplicateBudget;

    const autoToolChoice = "auto";

    const maxDuplicateToolCalls = duplicateBudget.maxDuplicateToolCalls;
    const maxTotalDuplicateSkips = duplicateBudget.maxTotalDuplicateSkips;
    const seenToolCallSignatures = duplicateBudget.seenToolCallSignatures;

    let totalDuplicateSkips = duplicateBudget.totalDuplicateSkips;
    let toolBlockId = 0;
    let turns = 0;
    let done = false;
    let writesApplied = 0;
    let hadToolCalls = false;

    const writeMode = mode === MODE_TEST || mode === MODE_REFACTOR;

    while (!done && turns < maxTurns) {
        const response = await openai.chat.completions.create({
            model: MODEL_NAME,
            messages: input,
            tools: toolset,
            tool_choice: toolChoice ?? autoToolChoice
        });

        const assistantMessage = response.choices[0].message;
        const assistantText = (assistantMessage.content ?? "").trim();
        const toolCalls = assistantMessage.tool_calls ?? [];

        const hasAssistantText = assistantText.length > 0;
        const hasToolCalls = toolCalls.length > 0;

        if (hasAssistantText) {
            logger.log(assistantText);
        } else {
            logger.log("[assistant returned no text]");
        }

        if (hasAssistantText || hasToolCalls) {
            input.push(assistantMessage);
        }

        if (!hasToolCalls) {
            done = true;
        } else {
            hadToolCalls = true;

            for (let i = 0; i < toolCalls.length; i += 1) {
                const call = toolCalls[i];
                const toolName = call.function.name;
                const rawArgs = call.function.arguments ?? "{}";

                toolBlockId += 1;
                logBlockHeader(logger, toolBlockId, `tool call: ${toolName}`);
                logger.log(`args=${rawArgs}`);

                try {
                    const duplicateCount = incrementDuplicateCount(
                        seenToolCallSignatures,
                        toolName,
                        rawArgs
                    );

                    const parsed = parseToolArgs(rawArgs);

                    if (!parsed.parsedOk) {
                        const errResult = createInvalidJsonToolResult(toolName, rawArgs);
                        printToolResult(toolName, errResult, logger);
                        pushToolResultMessage(input, call.id, errResult);
                    } else if (duplicateCount > maxDuplicateToolCalls) {
                        totalDuplicateSkips += 1;

                        const skipResult = createDuplicateSkipResult(
                            toolName,
                            rawArgs,
                            duplicateCount
                        );

                        printToolResult(toolName, skipResult, logger);
                        pushToolResultMessage(input, call.id, skipResult);

                        if (totalDuplicateSkips >= maxTotalDuplicateSkips) {
                            input.push(createStopRepeatingMessage());
                            done = true;
                        }
                    } else {
                        const args = parsed.args;
                        const isWriteTool =
                            toolName === TOOL_WRITE_FILE ||
                            toolName === TOOL_APPLY_PATCH;

                        if (isWriteTool && writeMode) {
                            ensureTargetsFromWrite(session.targetEvidence, toolName, args);

                            const canWrite = hasRequiredEvidenceToWrite(session.targetEvidence);
                            if (!canWrite) {
                                const blocked = {
                                    status: "tool_error",
                                    tool: toolName,
                                    message: requiredEvidenceMissingMessage(session.targetEvidence)
                                };

                                printToolResult(toolName, blocked, logger);
                                pushToolResultMessage(input, call.id, blocked);
                            } else {
                                try {
                                    const result = await callLocalTool(mode, toolName, args);
                                    writesApplied += 1;

                                    handleToolExecutionSuccess({
                                        session,
                                        toolName,
                                        args,
                                        result,
                                        printToolResult,
                                        logger
                                    });

                                    pushToolResultMessage(input, call.id, result);
                                } catch (err) {
                                    const errResult = buildToolErrorResultFromException(toolName, err);
                                    printToolResult(toolName, errResult, logger);
                                    pushToolResultMessage(input, call.id, errResult);
                                }
                            }
                        } else {
                            try {
                                const result = await callLocalTool(mode, toolName, args);

                                handleToolExecutionSuccess({
                                    session,
                                    toolName,
                                    args,
                                    result,
                                    printToolResult,
                                    logger
                                });

                                pushToolResultMessage(input, call.id, result);
                            } catch (err) {
                                const errResult = buildToolErrorResultFromException(toolName, err);
                                printToolResult(toolName, errResult, logger);
                                pushToolResultMessage(input, call.id, errResult);
                            }
                        }
                    }
                } finally {
                    logBlockFooter(logger, toolBlockId);
                }
            }
        }

        turns += 1;
    }

    duplicateBudget.totalDuplicateSkips = totalDuplicateSkips;

    return {
        done,
        turns,
        writesApplied,
        hadToolCalls
    };
};

const pushBuildResult = (input, title, result) => {
    const pretty = JSON.stringify(result, null, 2);
    input.push({
        role: ROLE_USER,
        content: `${title}\n\n${pretty}`
    });
};

const runBuildAndTests = async (mode, logger) => {
    const buildToolError = (toolName, err) => {
        const message = typeof err?.message === "string" ? err.message : String(err ?? "Unknown error");
        const stdout = typeof err?.stdout === "string" ? err.stdout : String(err?.stdout ?? "");
        const stderr = typeof err?.stderr === "string" ? err.stderr : String(err?.stderr ?? "");

        return {status: "tool_error", tool: toolName, message, stdout, stderr};
    };

    let prod;
    try {
        prod = await callLocalTool(mode, TOOL_COMPILE_PROD, {});
    } catch (err) {
        prod = buildToolErrorResultFromException(TOOL_COMPILE_PROD, err);
    }
    printToolResult(TOOL_COMPILE_PROD, prod, logger);

    if (prod.status !== "compiled") {
        return {ok: false, phase: "compile_prod", prod};
    }

    let tests;
    try {
        tests = await callLocalTool(mode, TOOL_COMPILE_TESTS, {});
    } catch (err) {
        tests = buildToolError(TOOL_COMPILE_TESTS, err);
    }
    printToolResult(TOOL_COMPILE_TESTS, tests, logger);

    if (tests.status !== "compiled") {
        return {ok: false, phase: "compile_tests", prod, tests};
    }

    let run;
    try {
        run = await callLocalTool(mode, TOOL_RUN_TESTS, {});
    } catch (err) {
        run = buildToolError(TOOL_RUN_TESTS, err);
    }
    printToolResult(TOOL_RUN_TESTS, run, logger);

    const exitCode = typeof run?.exit_code === "number" ? run.exit_code : 1;
    const passed = exitCode === 0;

    return {ok: passed, phase: "run_tests", prod, tests, run};
};

const runAgent = async (userPrompt, logger, isInteractive) => {
    const session = createSession(userPrompt, isInteractive);

    logger.log(`Mode: ${session.mode}`);

    const activeToolsAll = toolsForMode(session.mode, tools);
    const buildToolNames = new Set([TOOL_COMPILE_PROD, TOOL_COMPILE_TESTS, TOOL_RUN_TESTS]);
    const activeTools = activeToolsAll.filter(t => !buildToolNames.has(t.function?.name));

    const writeModeActive = (session.mode === MODE_TEST || session.mode === MODE_REFACTOR);
    const toolsToAdvertise = writeModeActive ? activeTools : activeToolsAll;

    logger.log(`Tools: ${toolsToAdvertise.map(t => t.function?.name).join(", ")}`);
    logger.log(`Interactive: ${isInteractive}`);

    if (isInteractive) {
        await runInteractiveSession(session, logger);
    } else {
        await runBatchSession(session, logger);
    }
};

const main = async () => {
    const argv = process.argv.slice(2);
    const logFileAbs = buildLogFilePath(argv);

    const logger = createLogger(logFileAbs);
    logger.log(`[log] writing to ${logFileAbs}`);

    const isInteractive = argv.includes(INTERACTIVE_FLAG);

    const userPrompt = argv
        .filter((a) => typeof a === "string" && !a.startsWith("--log=") && a !== INTERACTIVE_FLAG)
        .join(" ")
        .trim();

    if (userPrompt.length === 0) {
        throw new Error("Provide a prompt, e.g. node agent.mjs --interactive \"mode=test Generate tests for service.GitService\".");
    }

    try {
        await runAgent(userPrompt, logger, isInteractive);
    } finally {
        logger.close();
    }
};

main().catch((err) => {
    console.error(err?.stack ?? String(err));
    process.exitCode = 1;
});