package ai.moeru.airicraft.agent.observability;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.CompactionCheckpoint;
import ai.moeru.airicraft.agent.llm.VisionDescription;
import ai.moeru.airicraft.agent.llm.VisionRequest;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class OtelObservability implements AgentObservability {
	private static final String INSTRUMENTATION_NAME = "ai.moeru.airicraft";
	private static final ContextKey<String> THREAD_ID_KEY = ContextKey.named("airicraft.thread_id");
	private static final String DEFAULT_SERVICE_NAME = "airicraft-mod";
	private static final String WANDB_ENTITY_ATTRIBUTE = "wandb.entity";
	private static final String WANDB_PROJECT_ATTRIBUTE = "wandb.project";
	private static final String WB_ENTITY_ATTRIBUTE = "wb_entity";
	private static final String WB_PROJECT_ATTRIBUTE = "wb_project";
	private static final String WANDB_API_KEY_HEADER = "wandb-api-key";
	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String PROJECT_ID_HEADER = "project_id";
	private static final AttributeKey<Boolean> AIRICRAFT_WEAVE_CAPTURE_SIDECAR_EXPORTED =
		AttributeKey.booleanKey("airicraft.weave.capture_sidecar_exported");

	private static final AttributeKey<String> AIRICRAFT_THREAD_ID = AttributeKey.stringKey("airicraft.thread_id");
	private static final AttributeKey<Boolean> AIRICRAFT_TURN = AttributeKey.booleanKey("airicraft.turn");
	private static final AttributeKey<String> AIRICRAFT_REQUEST_KIND = AttributeKey.stringKey("airicraft.request_kind");
	private static final AttributeKey<String> AIRICRAFT_SESSION_MODE = AttributeKey.stringKey("airicraft.session_mode");
	private static final AttributeKey<String> AIRICRAFT_SENDER = AttributeKey.stringKey("airicraft.sender");
	private static final AttributeKey<String> AIRICRAFT_PRIMARY_INTERACTION_PLAYER = AttributeKey.stringKey("airicraft.primary_interaction_player");
	private static final AttributeKey<String> AIRICRAFT_ACTIVE_GOAL_TYPE = AttributeKey.stringKey("airicraft.active_goal_type");
	private static final AttributeKey<String> AIRICRAFT_PLANNER_VISION_MODE = AttributeKey.stringKey("airicraft.planner_vision_mode");
	private static final AttributeKey<String> AIRICRAFT_TOOL_NAME = AttributeKey.stringKey("airicraft.tool_name");
	private static final AttributeKey<String> AIRICRAFT_TOOL_NAMES = AttributeKey.stringKey("airicraft.tool_names");
	private static final AttributeKey<String> AIRICRAFT_FAILURE_TYPE = AttributeKey.stringKey("airicraft.failure_type");
	private static final AttributeKey<Long> AIRICRAFT_MESSAGE_COUNT = AttributeKey.longKey("airicraft.message_count");
	private static final AttributeKey<Boolean> AIRICRAFT_HAS_IMAGE = AttributeKey.booleanKey("airicraft.has_image_attachment");
	private static final AttributeKey<String> AIRICRAFT_IMAGE_DETAIL = AttributeKey.stringKey("airicraft.image_detail");
	private static final AttributeKey<Long> AIRICRAFT_PROMPT_LENGTH = AttributeKey.longKey("airicraft.prompt_length");
	private static final AttributeKey<Long> AIRICRAFT_REPLY_TEXT_LENGTH = AttributeKey.longKey("airicraft.reply_text_length");
	private static final AttributeKey<String> AIRICRAFT_INTENT_TYPE = AttributeKey.stringKey("airicraft.intent_type");
	private static final AttributeKey<String> AIRICRAFT_TOOL_REQUEST_TYPE = AttributeKey.stringKey("airicraft.tool_request_type");
	private static final AttributeKey<String> AIRICRAFT_COMPACTION_ACTIVE_GOAL = AttributeKey.stringKey("airicraft.compaction_active_goal");
	private static final AttributeKey<String> AIRICRAFT_IMAGE_MIME_TYPE = AttributeKey.stringKey("airicraft.image.mime_type");
	private static final AttributeKey<Long> AIRICRAFT_IMAGE_WIDTH = AttributeKey.longKey("airicraft.image.width");
	private static final AttributeKey<Long> AIRICRAFT_IMAGE_HEIGHT = AttributeKey.longKey("airicraft.image.height");
	private static final AttributeKey<Long> AIRICRAFT_IMAGE_SOURCE_WIDTH = AttributeKey.longKey("airicraft.image.source_width");
	private static final AttributeKey<Long> AIRICRAFT_IMAGE_SOURCE_HEIGHT = AttributeKey.longKey("airicraft.image.source_height");
	private static final AttributeKey<Long> AIRICRAFT_IMAGE_CAPTURED_AT_MS = AttributeKey.longKey("airicraft.image.captured_at_ms");
	private static final AttributeKey<String> GEN_AI_CONVERSATION_ID = AttributeKey.stringKey("gen_ai.conversation.id");
	private static final AttributeKey<String> GEN_AI_PROVIDER_NAME = AttributeKey.stringKey("gen_ai.provider.name");
	private static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
	private static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
	private static final AttributeKey<String> GEN_AI_PROMPT = AttributeKey.stringKey("gen_ai.prompt");
	private static final AttributeKey<String> GEN_AI_COMPLETION = AttributeKey.stringKey("gen_ai.completion");
	private static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
	private static final AttributeKey<String> GEN_AI_RESPONSE_MODEL = AttributeKey.stringKey("gen_ai.response.model");
	private static final AttributeKey<Long> GEN_AI_USAGE_INPUT = AttributeKey.longKey("gen_ai.usage.input_tokens");
	private static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT = AttributeKey.longKey("gen_ai.usage.output_tokens");
	private static final AttributeKey<Long> GEN_AI_USAGE_TOTAL = AttributeKey.longKey("gen_ai.usage.total_tokens");
	private static final AttributeKey<String> LLM_PROVIDER = AttributeKey.stringKey("llm.provider");
	private static final AttributeKey<String> LLM_MODEL_NAME = AttributeKey.stringKey("llm.model_name");
	private static final AttributeKey<String> OPENINFERENCE_SPAN_KIND = AttributeKey.stringKey("openinference.span.kind");
	private static final AttributeKey<String> WEAVE_SPAN_KIND = AttributeKey.stringKey("weave.span.kind");
	private static final AttributeKey<String> NETWORK_PROTOCOL_NAME = AttributeKey.stringKey("network.protocol.name");
	private static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
	private static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");
	private static final AttributeKey<String> URL_FULL = AttributeKey.stringKey("url.full");
	private static final AttributeKey<String> HTTP_REQUEST_METHOD = AttributeKey.stringKey("http.request.method");
	private static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE = AttributeKey.longKey("http.response.status_code");
	private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
	private static final AttributeKey<String> INPUT_VALUE = AttributeKey.stringKey("input.value");
	private static final AttributeKey<String> OUTPUT_VALUE = AttributeKey.stringKey("output.value");

	private final AgentConfig.ObservabilityConfig config;
	private final VendorAttributeAdapter vendorAttributeAdapter;
	private final WeaveCallsCompleteClient weaveCallsCompleteClient;
	private final SpanProcessor spanProcessor;
	private final SdkTracerProvider tracerProvider;
	private final OpenTelemetry openTelemetry;
	private final Tracer tracer;

	static AgentObservability create(AgentConfig.ObservabilityConfig config) {
		AgentConfig.ObservabilityConfig safeConfig = config == null ? AgentConfig.ObservabilityConfig.defaults() : config;
		if (!safeConfig.enabled()) {
			return NoopObservability.INSTANCE;
		}
		if (!"otlp_http".equalsIgnoreCase(safeConfig.exporter())) {
			Airicraft.LOGGER.warn("Unsupported observability exporter {}; tracing disabled", safeConfig.exporter());
			return NoopObservability.INSTANCE;
		}
		if (safeConfig.otlpEndpoint().isBlank()) {
			Airicraft.LOGGER.warn("Observability is enabled but otlpEndpoint is blank; tracing disabled");
			return NoopObservability.INSTANCE;
		}
		if ("weave".equalsIgnoreCase(safeConfig.vendorProfile())) {
			validateWeaveProjectRouting(safeConfig);
		}
		Airicraft.LOGGER.info(
			"Observability enabled exporter={} endpoint={} vendorProfile={} debugLogExports={} captureInputs={} captureOutputs={} captureImages={}",
			safeConfig.exporter(),
			safeConfig.otlpEndpoint(),
			safeConfig.vendorProfile(),
			safeConfig.debugLogExports(),
			safeConfig.captureInputs(),
			safeConfig.captureOutputs(),
			safeConfig.captureImages()
		);

		Map<String, String> exporterHeaders = effectiveOtlpHeaders(safeConfig);
		var exporterBuilder = OtlpHttpSpanExporter.builder()
			.setEndpoint(safeConfig.otlpEndpoint());
		for (Map.Entry<String, String> entry : exporterHeaders.entrySet()) {
			exporterBuilder.addHeader(entry.getKey(), entry.getValue());
		}
		SpanExporter exporter = exporterBuilder.build();
		WeaveCallsCompleteClient weaveCallsCompleteClient = WeaveCallsCompleteClient.create(safeConfig);
		if (weaveCallsCompleteClient != null) {
			exporter = new FilteringSpanExporter(
				exporter,
				spanData -> !Boolean.TRUE.equals(spanData.getAttributes().get(AIRICRAFT_WEAVE_CAPTURE_SIDECAR_EXPORTED))
			);
		}
		if (safeConfig.debugLogExports()) {
			exporter = new DebugLoggingSpanExporter(exporter);
		}
		SpanProcessor spanProcessor = safeConfig.debugLogExports()
			? SimpleSpanProcessor.create(exporter)
			: BatchSpanProcessor.builder(exporter).build();
		return new OtelObservability(safeConfig, spanProcessor, weaveCallsCompleteClient);
	}

	OtelObservability(AgentConfig.ObservabilityConfig config, SpanProcessor spanProcessor) {
		this(config, spanProcessor, null);
	}

	OtelObservability(
		AgentConfig.ObservabilityConfig config,
		SpanProcessor spanProcessor,
		WeaveCallsCompleteClient weaveCallsCompleteClient
	) {
		this.config = Objects.requireNonNull(config, "config");
		this.vendorAttributeAdapter = VendorAttributeAdapter.forProfile(config.vendorProfile());
		this.weaveCallsCompleteClient = weaveCallsCompleteClient;
		this.spanProcessor = Objects.requireNonNull(spanProcessor, "spanProcessor");
		Resource resource = Resource.getDefault().merge(Resource.create(buildResourceAttributes(config)));
		this.tracerProvider = SdkTracerProvider.builder()
			.setResource(resource)
			.addSpanProcessor(spanProcessor)
			.build();
		this.openTelemetry = OpenTelemetrySdk.builder()
			.setTracerProvider(tracerProvider)
			.build();
		this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
	}

	@Override
	public Context startTurnSpan(PlannerRequest request, String threadId) {
		String safeThreadId = threadId == null || threadId.isBlank() ? "session:none:player:unknown" : threadId;
		if (config.debugLogExports()) {
			Airicraft.LOGGER.info(
				"Observability startTurnSpan threadId={} sender={} sessionMode={}",
				safeThreadId,
				request == null ? null : request.senderName(),
				request == null || request.sessionMode() == null ? null : request.sessionMode().name()
			);
		}
		if (vendorAttributeAdapter.useThreadContextOnly()) {
			return Context.root().with(THREAD_ID_KEY, safeThreadId);
		}
		Span span = tracer.spanBuilder(TURN_SPAN_NAME)
			.setNoParent()
			.setSpanKind(SpanKind.INTERNAL)
			.startSpan();
		span.setAttribute(AIRICRAFT_THREAD_ID, safeThreadId);
		span.setAttribute(AIRICRAFT_TURN, true);
		span.setAttribute(GEN_AI_CONVERSATION_ID, safeThreadId);
		vendorAttributeAdapter.onThreadId(span, safeThreadId);
		vendorAttributeAdapter.onTurn(span, true);
		if (request != null) {
			span.setAttribute(AIRICRAFT_SESSION_MODE, request.sessionMode().name());
			span.setAttribute(AIRICRAFT_SENDER, normalizeValue(request.senderName()));
			if (request.primaryInteractionPlayer() != null && !request.primaryInteractionPlayer().isBlank()) {
				span.setAttribute(AIRICRAFT_PRIMARY_INTERACTION_PLAYER, request.primaryInteractionPlayer());
			}
			GoalSnapshot activeGoal = request.activeGoal();
			if (activeGoal != null && activeGoal.type() != null) {
				span.setAttribute(AIRICRAFT_ACTIVE_GOAL_TYPE, activeGoal.type().name());
			}
		}
		return Context.root().with(span).with(THREAD_ID_KEY, safeThreadId);
	}

	@Override
	public Context startChildSpan(String name, Context parent) {
		Context safeParent = parent == null ? Context.root() : parent;
		String parentThreadId = safeParent.get(THREAD_ID_KEY);
		if (config.debugLogExports()) {
			Airicraft.LOGGER.info(
				"Observability startChildSpan name={} parentThreadId={} currentThreadId={}",
				name,
				parentThreadId,
				Context.current().get(THREAD_ID_KEY)
			);
		}
		var spanBuilder = tracer.spanBuilder(name)
			.setSpanKind(spanKind(name));
		if (vendorAttributeAdapter.useThreadContextOnly()) {
			spanBuilder.setNoParent();
		}
		else {
			spanBuilder.setParent(safeParent);
		}
		Span span = spanBuilder.startSpan();
		String threadId = parentThreadId;
		if (threadId != null && !threadId.isBlank()) {
			span.setAttribute(AIRICRAFT_THREAD_ID, threadId);
			span.setAttribute(GEN_AI_CONVERSATION_ID, threadId);
			vendorAttributeAdapter.onThreadId(span, threadId);
		}
		String requestKind = requestKind(name);
		if (requestKind != null) {
			span.setAttribute(AIRICRAFT_REQUEST_KIND, requestKind);
		}
		if (vendorAttributeAdapter.exposeAsThreadRow(name)) {
			span.setAttribute(AIRICRAFT_TURN, true);
			vendorAttributeAdapter.onTurn(span, true);
		}
		return safeParent.with(span);
	}

	@Override
	public void setSpanAttribute(Context context, String key, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		Span span = Span.fromContext(context);
		span.setAttribute(key, value);
		if ("airicraft.thread_id".equals(key)) {
			vendorAttributeAdapter.onThreadId(span, value);
		}
	}

	@Override
	public void setSpanAttribute(Context context, String key, boolean value) {
		Span span = Span.fromContext(context);
		span.setAttribute(key, value);
		if ("airicraft.turn".equals(key)) {
			vendorAttributeAdapter.onTurn(span, value);
		}
	}

	@Override
	public void setSpanAttribute(Context context, String key, long value) {
		Span.fromContext(context).setAttribute(key, value);
	}

	@Override
	public void recordImageCapture(Context context, FirstPersonScreenshotService.CapturedScreenshot capture) {
		if (!config.captureImages() || capture == null) {
			return;
		}
		Span span = Span.fromContext(context);
		span.setAttribute(AIRICRAFT_HAS_IMAGE, true);
		span.setAttribute(AIRICRAFT_IMAGE_MIME_TYPE, TraceSanitizer.sanitizedMimeType(capture));
		span.setAttribute(AIRICRAFT_IMAGE_WIDTH, (long) capture.width());
		span.setAttribute(AIRICRAFT_IMAGE_HEIGHT, (long) capture.height());
		span.setAttribute(AIRICRAFT_IMAGE_SOURCE_WIDTH, (long) capture.sourceWidth());
		span.setAttribute(AIRICRAFT_IMAGE_SOURCE_HEIGHT, (long) capture.sourceHeight());
		span.setAttribute(AIRICRAFT_IMAGE_CAPTURED_AT_MS, capture.capturedAtMs());
		String threadId = context == null ? null : context.get(THREAD_ID_KEY);
		if (weaveCallsCompleteClient != null && weaveCallsCompleteClient.exportImageCapture(threadId, capture)) {
			span.setAttribute(AIRICRAFT_WEAVE_CAPTURE_SIDECAR_EXPORTED, true);
			return;
		}
		String imagePayload = TraceSanitizer.imageCapturePayloadForTrace(capture);
		if (!imagePayload.isBlank()) {
			span.setAttribute(OUTPUT_VALUE, imagePayload);
		}
		String completion = TraceSanitizer.imageCaptureCompletionForGenAi(capture);
		if (!completion.isBlank()) {
			span.setAttribute(GEN_AI_COMPLETION, completion);
		}
	}

	@Override
	public void recordLlmRequest(
		Context context,
		String providerName,
		URI endpoint,
		String model,
		long timeoutMillis,
		LlmConversation conversation,
		String requestBody
	) {
		Span span = Span.fromContext(context);
		recordTransport(span, providerName, endpoint, model, timeoutMillis);
		span.setAttribute(AIRICRAFT_MESSAGE_COUNT, (long) conversation.messages().size());
		span.setAttribute(AIRICRAFT_HAS_IMAGE, conversation.messages().stream().anyMatch(message -> message.imageAttachment() != null));
		span.setAttribute(AIRICRAFT_IMAGE_DETAIL, TraceSanitizer.sanitizeImageDetailSummary(conversation));
		span.setAttribute(AIRICRAFT_PROMPT_LENGTH, TraceSanitizer.conversationPromptLength(conversation));
		if (config.captureInputs()) {
			String sanitizedRequestBody = TraceSanitizer.sanitizeRequestPayloadForTrace(requestBody, config.captureImages());
			if (!sanitizedRequestBody.isBlank()) {
				span.setAttribute(INPUT_VALUE, sanitizedRequestBody);
			}
			else {
				span.setAttribute(INPUT_VALUE, TraceSanitizer.sanitizeConversationForTrace(conversation, config.captureImages()));
			}
			String prompt = TraceSanitizer.sanitizePromptFromRequestPayload(requestBody, config.captureImages());
			if (!prompt.isBlank()) {
				span.setAttribute(GEN_AI_PROMPT, prompt);
			}
			else {
				span.setAttribute(GEN_AI_PROMPT, TraceSanitizer.sanitizeConversationPromptForGenAi(conversation, config.captureImages()));
			}
			String systemPrompt = TraceSanitizer.sanitizeSystemFromRequestPayload(requestBody);
			if (systemPrompt.isBlank()) {
				systemPrompt = TraceSanitizer.sanitizeConversationSystemPrompt(conversation);
			}
			if (!systemPrompt.isBlank()) {
				span.setAttribute(GEN_AI_SYSTEM, systemPrompt);
			}
		}
	}

	@Override
	public void recordFailedLlmInput(Context context, LlmConversation conversation, String requestBody) {
		Span span = Span.fromContext(context);
		String sanitizedRequestBody = TraceSanitizer.sanitizeRequestPayloadForTrace(requestBody, config.captureImages());
		if (!sanitizedRequestBody.isBlank()) {
			span.setAttribute(INPUT_VALUE, sanitizedRequestBody);
		}
		else if (conversation != null) {
			span.setAttribute(INPUT_VALUE, TraceSanitizer.sanitizeConversationForTrace(conversation, config.captureImages()));
		}

		String prompt = TraceSanitizer.sanitizePromptFromRequestPayload(requestBody, config.captureImages());
		if (!prompt.isBlank()) {
			span.setAttribute(GEN_AI_PROMPT, prompt);
		}
		else if (conversation != null) {
			span.setAttribute(GEN_AI_PROMPT, TraceSanitizer.sanitizeConversationPromptForGenAi(conversation, config.captureImages()));
		}

		String systemPrompt = TraceSanitizer.sanitizeSystemFromRequestPayload(requestBody);
		if (systemPrompt.isBlank() && conversation != null) {
			systemPrompt = TraceSanitizer.sanitizeConversationSystemPrompt(conversation);
		}
		if (!systemPrompt.isBlank()) {
			span.setAttribute(GEN_AI_SYSTEM, systemPrompt);
		}
	}

	@Override
	public void recordLlmRequest(
		Context context,
		String providerName,
		URI endpoint,
		String model,
		long timeoutMillis,
		VisionRequest request,
		String imageDetail,
		String requestBody
	) {
		Span span = Span.fromContext(context);
		recordTransport(span, providerName, endpoint, model, timeoutMillis);
		span.setAttribute(AIRICRAFT_HAS_IMAGE, true);
		span.setAttribute(AIRICRAFT_IMAGE_DETAIL, normalizeValue(imageDetail));
		span.setAttribute(AIRICRAFT_PROMPT_LENGTH, request.prompt() == null ? 0L : request.prompt().length());
		span.setAttribute(AIRICRAFT_IMAGE_MIME_TYPE, normalizeValue(request.mimeType()));
		span.setAttribute(AIRICRAFT_IMAGE_CAPTURED_AT_MS, request.capturedAtMs());
		if (config.captureInputs()) {
			String sanitizedRequestBody = TraceSanitizer.sanitizeRequestPayloadForTrace(requestBody, config.captureImages());
			if (!sanitizedRequestBody.isBlank()) {
				span.setAttribute(INPUT_VALUE, sanitizedRequestBody);
			}
			else {
				span.setAttribute(INPUT_VALUE, TraceSanitizer.sanitizeVisionRequestForTrace(request, imageDetail));
			}
			String prompt = TraceSanitizer.sanitizePromptFromRequestPayload(requestBody, config.captureImages());
			if (!prompt.isBlank()) {
				span.setAttribute(GEN_AI_PROMPT, prompt);
			}
			else {
				span.setAttribute(GEN_AI_PROMPT, normalizeValue(request.prompt()));
			}
			String systemPrompt = TraceSanitizer.sanitizeSystemFromRequestPayload(requestBody);
			if (!systemPrompt.isBlank()) {
				span.setAttribute(GEN_AI_SYSTEM, systemPrompt);
			}
		}
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, PlannerResponse plannerResponse) {
		Span span = Span.fromContext(context);
		recordResponseMetadata(span, statusCode, responseModel, usage);
		if (plannerResponse == null) {
			return;
		}
		String replyText = plannerResponse.replyText() == null ? "" : plannerResponse.replyText();
		span.setAttribute(AIRICRAFT_REPLY_TEXT_LENGTH, (long) replyText.length());
		if (plannerResponse.intent() != null && plannerResponse.intent().type() != null) {
			span.setAttribute(AIRICRAFT_INTENT_TYPE, normalizeValue(plannerResponse.intent().type()));
		}
			if (plannerResponse.toolRequest() != null && plannerResponse.toolRequest().type() != null) {
				span.setAttribute(AIRICRAFT_TOOL_REQUEST_TYPE, normalizeValue(plannerResponse.toolRequest().type()));
			}
				if (!plannerResponse.toolCalls().isEmpty()) {
					span.setAttribute(AIRICRAFT_TOOL_NAMES, normalizeValue(plannerResponse.toolCalls().stream()
						.map(toolCall -> toolCall.name() == null ? "" : toolCall.name())
						.filter(name -> !name.isBlank())
						.collect(java.util.stream.Collectors.joining(","))));
					if (plannerResponse.toolCall() != null && plannerResponse.toolCall().name() != null) {
						span.setAttribute(AIRICRAFT_TOOL_NAME, normalizeValue(plannerResponse.toolCall().name()));
					}
				}
		if (config.captureOutputs()) {
			span.setAttribute(OUTPUT_VALUE, TraceSanitizer.sanitizePlannerResponseForTrace(plannerResponse));
			span.setAttribute(GEN_AI_COMPLETION, TraceSanitizer.sanitizePlannerCompletionForGenAi(plannerResponse));
		}
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, String rawResponseBody) {
		Span span = Span.fromContext(context);
		recordResponseMetadata(span, statusCode, responseModel, usage);
		// Failure paths still need the provider response body for parse debugging even when
		// normal output capture is disabled.
		String outputValue = TraceSanitizer.sanitizeChatResponseForTrace(rawResponseBody);
		if (!outputValue.isBlank()) {
			span.setAttribute(OUTPUT_VALUE, outputValue);
		}
		String completion = TraceSanitizer.sanitizeChatCompletionForGenAi(rawResponseBody);
		if (!completion.isBlank()) {
			span.setAttribute(GEN_AI_COMPLETION, completion);
		}
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, CompactionCheckpoint checkpoint) {
		Span span = Span.fromContext(context);
		recordResponseMetadata(span, statusCode, responseModel, usage);
		if (checkpoint == null) {
			return;
		}
		span.setAttribute(AIRICRAFT_COMPACTION_ACTIVE_GOAL, normalizeValue(checkpoint.activeGoal()));
		if (config.captureOutputs()) {
			span.setAttribute(OUTPUT_VALUE, TraceSanitizer.sanitizeCheckpointForTrace(checkpoint));
			span.setAttribute(GEN_AI_COMPLETION, TraceSanitizer.sanitizeCheckpointCompletionForGenAi(checkpoint));
		}
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, VisionDescription visionDescription) {
		Span span = Span.fromContext(context);
		recordResponseMetadata(span, statusCode, responseModel, usage);
		if (visionDescription == null) {
			return;
		}
		span.setAttribute(AIRICRAFT_REPLY_TEXT_LENGTH, (long) visionDescription.text().length());
		if (config.captureOutputs()) {
			span.setAttribute(OUTPUT_VALUE, TraceSanitizer.sanitizeVisionDescriptionForTrace(visionDescription));
			span.setAttribute(GEN_AI_COMPLETION, TraceSanitizer.sanitizeVisionCompletionForGenAi(visionDescription));
		}
	}

	@Override
	public void recordFailure(Context context, String failureType, String message, Throwable throwable) {
		Span span = Span.fromContext(context);
		String safeFailureType = TraceSanitizer.safeToolCode(failureType);
		String safeMessage = TraceSanitizer.safeFailureMessage(message);
		if (!safeFailureType.isBlank()) {
			span.setAttribute(AIRICRAFT_FAILURE_TYPE, safeFailureType);
			span.setAttribute(ERROR_TYPE, safeFailureType);
		}
		span.setStatus(StatusCode.ERROR, safeMessage);
		if (throwable != null) {
			span.recordException(throwable);
		}
	}

	@Override
	public void endSpan(Context context) {
		Span.fromContext(context).end();
	}

	@Override
	public void shutdown() {
		try {
			tracerProvider.forceFlush().join(5_000, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
		catch (Exception exception) {
			Airicraft.LOGGER.debug("Failed to flush tracing provider cleanly", exception);
		}
		try {
			tracerProvider.shutdown().join(5_000, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
		catch (Exception exception) {
			Airicraft.LOGGER.debug("Failed to shut down tracing provider cleanly", exception);
		}
	}

	private void recordTransport(Span span, String providerName, URI endpoint, String model, long timeoutMillis) {
		span.setAttribute(GEN_AI_PROVIDER_NAME, normalizeValue(providerName));
		span.setAttribute(GEN_AI_OPERATION_NAME, "chat");
		span.setAttribute(GEN_AI_REQUEST_MODEL, normalizeValue(model));
		span.setAttribute(LLM_PROVIDER, normalizeValue(providerName));
		span.setAttribute(LLM_MODEL_NAME, normalizeValue(model));
		span.setAttribute(OPENINFERENCE_SPAN_KIND, "llm");
		span.setAttribute(WEAVE_SPAN_KIND, "llm");
		span.setAttribute(NETWORK_PROTOCOL_NAME, "http");
		span.setAttribute(HTTP_REQUEST_METHOD, "POST");
		String host = TraceSanitizer.host(endpoint);
		if (!host.isBlank()) {
			span.setAttribute(SERVER_ADDRESS, host);
		}
		long port = TraceSanitizer.port(endpoint);
		if (port >= 0L) {
			span.setAttribute(SERVER_PORT, port);
		}
		String url = TraceSanitizer.normalizedUrl(endpoint);
		if (!url.isBlank()) {
			span.setAttribute(URL_FULL, url);
		}
		span.setAttribute("airicraft.timeout_ms", timeoutMillis);
	}

	private void recordResponseMetadata(Span span, Integer statusCode, String responseModel, LlmUsageSnapshot usage) {
		if (statusCode != null) {
			span.setAttribute(HTTP_RESPONSE_STATUS_CODE, statusCode.longValue());
		}
		if (responseModel != null && !responseModel.isBlank()) {
			span.setAttribute(GEN_AI_RESPONSE_MODEL, responseModel);
		}
		if (usage != null) {
			if (usage.promptTokens() != null) {
				span.setAttribute(GEN_AI_USAGE_INPUT, usage.promptTokens().longValue());
			}
			if (usage.completionTokens() != null) {
				span.setAttribute(GEN_AI_USAGE_OUTPUT, usage.completionTokens().longValue());
			}
			if (usage.totalTokens() != null) {
				span.setAttribute(GEN_AI_USAGE_TOTAL, usage.totalTokens().longValue());
			}
		}
	}

	private static String normalizeValue(String value) {
		return value == null ? "" : value;
	}

	private static SpanKind spanKind(String name) {
		return switch (name) {
			case AgentObservability.PLANNER_REQUEST_SPAN_NAME,
				AgentObservability.PLANNER_COMPACTION_SPAN_NAME,
				AgentObservability.FOLLOW_UP_SPAN_NAME,
				AgentObservability.VISION_DESCRIBE_SPAN_NAME -> SpanKind.CLIENT;
			default -> SpanKind.INTERNAL;
		};
	}

	private static String requestKind(String name) {
		return switch (name) {
			case AgentObservability.PLANNER_REQUEST_SPAN_NAME -> "planner";
			case AgentObservability.PLANNER_COMPACTION_SPAN_NAME -> "compaction";
			case AgentObservability.FOLLOW_UP_SPAN_NAME -> "follow_up";
			case AgentObservability.VISION_DESCRIBE_SPAN_NAME -> "vision";
			default -> null;
		};
	}

	private static Attributes buildResourceAttributes(AgentConfig.ObservabilityConfig config) {
		AttributesBuilder builder = Attributes.builder();
		builder.put(AttributeKey.stringKey("service.name"), DEFAULT_SERVICE_NAME);
		for (Map.Entry<String, String> entry : config.resourceAttributes().entrySet()) {
			String key = entry.getKey();
			if (key == null || key.isBlank()) {
				continue;
			}
			builder.put(AttributeKey.stringKey(key), entry.getValue() == null ? "" : entry.getValue());
		}
		if ("weave".equalsIgnoreCase(config.vendorProfile())) {
			String entity = config.resourceAttributes().getOrDefault(WANDB_ENTITY_ATTRIBUTE, "");
			String project = config.resourceAttributes().getOrDefault(WANDB_PROJECT_ATTRIBUTE, "");
			if (!entity.isBlank()) {
				builder.put(AttributeKey.stringKey(WB_ENTITY_ATTRIBUTE), entity);
			}
			if (!project.isBlank()) {
				builder.put(AttributeKey.stringKey(WB_PROJECT_ATTRIBUTE), project);
			}
		}
		return builder.build();
	}

	private static void validateWeaveProjectRouting(AgentConfig.ObservabilityConfig config) {
		String entity = config.resourceAttributes().getOrDefault(WANDB_ENTITY_ATTRIBUTE, "");
		String project = config.resourceAttributes().getOrDefault(WANDB_PROJECT_ATTRIBUTE, "");
		if (!entity.isBlank() && !project.isBlank()) {
			return;
		}
		Airicraft.LOGGER.warn(
			"Weave vendorProfile is enabled but resourceAttributes {} and {} are not both set; traces may not route to the intended Weave project",
			WANDB_ENTITY_ATTRIBUTE,
			WANDB_PROJECT_ATTRIBUTE
		);
	}

	private static Map<String, String> effectiveOtlpHeaders(AgentConfig.ObservabilityConfig config) {
		Map<String, String> headers = new LinkedHashMap<>(config.otlpHeaders());
		if (!"weave".equalsIgnoreCase(config.vendorProfile())) {
			return Map.copyOf(headers);
		}

		String apiKey = headers.getOrDefault(WANDB_API_KEY_HEADER, "");
		if (!apiKey.isBlank() && !headers.containsKey(AUTHORIZATION_HEADER)) {
			String basic = Base64.getEncoder().encodeToString(("api:" + apiKey).getBytes(StandardCharsets.UTF_8));
			headers.put(AUTHORIZATION_HEADER, "Basic " + basic);
		}

		String entity = config.resourceAttributes().getOrDefault(WANDB_ENTITY_ATTRIBUTE, "");
		String project = config.resourceAttributes().getOrDefault(WANDB_PROJECT_ATTRIBUTE, "");
		if (!entity.isBlank() && !project.isBlank() && !headers.containsKey(PROJECT_ID_HEADER)) {
			headers.put(PROJECT_ID_HEADER, entity + "/" + project);
		}
		return Map.copyOf(headers);
	}
}
