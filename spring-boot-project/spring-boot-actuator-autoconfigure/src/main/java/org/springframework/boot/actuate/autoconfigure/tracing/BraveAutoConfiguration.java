/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.actuate.autoconfigure.tracing;

import java.util.Collections;
import java.util.List;

import brave.Tracer;
import brave.Tracing;
import brave.Tracing.Builder;
import brave.TracingCustomizer;
import brave.baggage.BaggageField;
import brave.baggage.BaggagePropagation;
import brave.baggage.BaggagePropagation.FactoryBuilder;
import brave.baggage.BaggagePropagationConfig;
import brave.baggage.BaggagePropagationCustomizer;
import brave.baggage.CorrelationScopeConfig;
import brave.baggage.CorrelationScopeCustomizer;
import brave.baggage.CorrelationScopeDecorator;
import brave.context.slf4j.MDCScopeDecorator;
import brave.handler.SpanHandler;
import brave.http.HttpClientHandler;
import brave.http.HttpClientRequest;
import brave.http.HttpClientResponse;
import brave.http.HttpServerHandler;
import brave.http.HttpServerRequest;
import brave.http.HttpServerResponse;
import brave.http.HttpTracing;
import brave.propagation.B3Propagation;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.ScopeDecorator;
import brave.propagation.CurrentTraceContextCustomizer;
import brave.propagation.Propagation.Factory;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.sampler.Sampler;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveHttpClientHandler;
import io.micrometer.tracing.brave.bridge.BraveHttpServerHandler;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.brave.bridge.CompositeSpanHandler;
import io.micrometer.tracing.brave.bridge.W3CPropagation;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.micrometer.tracing.exporter.SpanFilter;
import io.micrometer.tracing.exporter.SpanReporter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for Brave.
 *
 * @author Moritz Halbritter
 * @author Marcin Grzejszczak
 * @since 3.0.0
 */
@AutoConfiguration(before = MicrometerTracingAutoConfiguration.class)
@ConditionalOnClass({ Tracer.class, BraveTracer.class })
@EnableConfigurationProperties(TracingProperties.class)
@ConditionalOnEnabledTracing
public class BraveAutoConfiguration {

	private static final BraveBaggageManager BRAVE_BAGGAGE_MANAGER = new BraveBaggageManager();

	/**
	 * Default value for application name if {@code spring.application.name} is not set.
	 */
	private static final String DEFAULT_APPLICATION_NAME = "application";

	@Bean
	@ConditionalOnMissingBean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	CompositeSpanHandler compositeSpanHandler(ObjectProvider<SpanExportingPredicate> predicates,
			ObjectProvider<SpanReporter> reporters, ObjectProvider<SpanFilter> filters) {
		return new CompositeSpanHandler(predicates.orderedStream().toList(), reporters.orderedStream().toList(),
				filters.orderedStream().toList());
	}

	@Bean
	@ConditionalOnMissingBean
	public Tracing braveTracing(Environment environment, List<SpanHandler> spanHandlers,
			List<TracingCustomizer> tracingCustomizers, CurrentTraceContext currentTraceContext,
			Factory propagationFactory, Sampler sampler) {
		String applicationName = environment.getProperty("spring.application.name", DEFAULT_APPLICATION_NAME);
		Builder builder = Tracing.newBuilder().currentTraceContext(currentTraceContext).traceId128Bit(true)
				.supportsJoin(false).propagationFactory(propagationFactory).sampler(sampler)
				.localServiceName(applicationName);
		for (SpanHandler spanHandler : spanHandlers) {
			builder.addSpanHandler(spanHandler);
		}
		for (TracingCustomizer tracingCustomizer : tracingCustomizers) {
			tracingCustomizer.customize(builder);
		}
		return builder.build();
	}

	@Bean
	@ConditionalOnMissingBean
	public brave.Tracer braveTracer(Tracing tracing) {
		return tracing.tracer();
	}

	@Bean
	@ConditionalOnMissingBean
	public CurrentTraceContext braveCurrentTraceContext(List<CurrentTraceContext.ScopeDecorator> scopeDecorators,
			List<CurrentTraceContextCustomizer> currentTraceContextCustomizers) {
		ThreadLocalCurrentTraceContext.Builder builder = ThreadLocalCurrentTraceContext.newBuilder();
		for (ScopeDecorator scopeDecorator : scopeDecorators) {
			builder.addScopeDecorator(scopeDecorator);
		}
		for (CurrentTraceContextCustomizer currentTraceContextCustomizer : currentTraceContextCustomizers) {
			currentTraceContextCustomizer.customize(builder);
		}
		return builder.build();
	}

	@Bean
	@ConditionalOnMissingBean
	public Sampler braveSampler(TracingProperties properties) {
		return Sampler.create(properties.getSampling().getProbability());
	}

	@Bean
	@ConditionalOnMissingBean
	public HttpTracing httpTracing(Tracing tracing) {
		return HttpTracing.newBuilder(tracing).build();
	}

	@Bean
	@ConditionalOnMissingBean
	public HttpServerHandler<HttpServerRequest, HttpServerResponse> httpServerHandler(HttpTracing httpTracing) {
		return HttpServerHandler.create(httpTracing);
	}

	@Bean
	@ConditionalOnMissingBean
	public HttpClientHandler<HttpClientRequest, HttpClientResponse> httpClientHandler(HttpTracing httpTracing) {
		return HttpClientHandler.create(httpTracing);
	}

	@Bean
	@ConditionalOnMissingBean
	BraveTracer braveTracerBridge(brave.Tracer tracer, CurrentTraceContext currentTraceContext) {
		return new BraveTracer(tracer, new BraveCurrentTraceContext(currentTraceContext), BRAVE_BAGGAGE_MANAGER);
	}

	@Bean
	@ConditionalOnMissingBean
	BravePropagator bravePropagator(Tracing tracing) {
		return new BravePropagator(tracing);
	}

	@Bean
	@ConditionalOnMissingBean
	BraveHttpServerHandler braveHttpServerHandler(
			HttpServerHandler<HttpServerRequest, HttpServerResponse> httpServerHandler) {
		return new BraveHttpServerHandler(httpServerHandler);
	}

	@Bean
	@ConditionalOnMissingBean
	BraveHttpClientHandler braveHttpClientHandler(
			HttpClientHandler<HttpClientRequest, HttpClientResponse> httpClientHandler) {
		return new BraveHttpClientHandler(httpClientHandler);
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(value = "management.tracing.baggage.enabled", havingValue = "false")
	static class BraveNoBaggageConfiguration {

		@Bean
		@ConditionalOnMissingBean
		Factory propagationFactory(TracingProperties tracing) {
			return switch (tracing.getPropagation().getType()) {
				case B3 ->
					B3Propagation.newFactoryBuilder().injectFormat(B3Propagation.Format.SINGLE_NO_PARENT).build();
				case W3C -> new W3CPropagation();
			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(value = "management.tracing.baggage.enabled", matchIfMissing = true)
	static class BraveBaggageConfiguration {

		private final TracingProperties tracingProperties;

		BraveBaggageConfiguration(TracingProperties tracingProperties) {
			this.tracingProperties = tracingProperties;
		}

		@Bean
		@ConditionalOnMissingBean
		BaggagePropagation.FactoryBuilder propagationFactoryBuilder(
				ObjectProvider<BaggagePropagationCustomizer> baggagePropagationCustomizers) {
			Factory delegate = switch (this.tracingProperties.getPropagation().getType()) {
				case B3 ->
					B3Propagation.newFactoryBuilder().injectFormat(B3Propagation.Format.SINGLE_NO_PARENT).build();
				case W3C -> new W3CPropagation(BRAVE_BAGGAGE_MANAGER, Collections.emptyList());
			};
			FactoryBuilder builder = BaggagePropagation.newFactoryBuilder(delegate);
			baggagePropagationCustomizers.orderedStream().forEach((customizer) -> customizer.customize(builder));
			return builder;
		}

		@Bean
		@Order(0)
		BaggagePropagationCustomizer remoteFieldsBaggagePropagationCustomizer() {
			return (builder) -> {
				List<String> remoteFields = this.tracingProperties.getBaggage().getRemoteFields();
				for (String fieldName : remoteFields) {
					builder.add(BaggagePropagationConfig.SingleBaggageField.remote(BaggageField.create(fieldName)));
				}
			};
		}

		@Bean
		@ConditionalOnMissingBean
		Factory propagationFactory(BaggagePropagation.FactoryBuilder factoryBuilder) {
			return factoryBuilder.build();
		}

		@Bean
		@ConditionalOnMissingBean
		CorrelationScopeDecorator.Builder mdcCorrelationScopeDecoratorBuilder(
				ObjectProvider<CorrelationScopeCustomizer> correlationScopeCustomizers) {
			CorrelationScopeDecorator.Builder builder = MDCScopeDecorator.newBuilder();
			correlationScopeCustomizers.orderedStream().forEach((customizer) -> customizer.customize(builder));
			return builder;
		}

		@Bean
		@Order(0)
		@ConditionalOnProperty(prefix = "management.tracing.baggage.correlation", name = "enabled",
				matchIfMissing = true)
		CorrelationScopeCustomizer correlationFieldsCorrelationScopeCustomizer() {
			return (builder) -> {
				List<String> correlationFields = this.tracingProperties.getBaggage().getCorrelation().getFields();
				for (String field : correlationFields) {
					builder.add(CorrelationScopeConfig.SingleCorrelationField.newBuilder(BaggageField.create(field))
							.flushOnUpdate().build());
				}
			};
		}

		@Bean
		@ConditionalOnMissingBean(CorrelationScopeDecorator.class)
		ScopeDecorator correlationScopeDecorator(CorrelationScopeDecorator.Builder builder) {
			return builder.build();
		}

	}

}
