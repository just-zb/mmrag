package com.baozhu.mmrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Type-safe binding for the {@code multimodal.*} keys in
 * {@code application.yml}. Centralises the runtime switches for
 * embedding strategy, retrieval defaults, reranker provider, generator
 * provider, and the Ragas tracer.
 *
 * <p>The individual adapter / strategy classes still read narrow
 * {@code @Value} keys for cohesion; this binding is the canonical place
 * to look at the full multimodal configuration shape.
 */
@Configuration
@ConfigurationProperties(prefix = "multimodal")
public class MultimodalProperties {

    private Embedding embedding = new Embedding();
    private Retrieval retrieval = new Retrieval();
    private Reranker reranker = new Reranker();
    private Generator generator = new Generator();
    private Captioning captioning = new Captioning();
    private Tracer tracer = new Tracer();

    public static class Embedding {
        /** {@code siglip} (default) | {@code caption} | {@code dashscope}. */
        private String strategy = "siglip";
        private Siglip siglip = new Siglip();
        public String getStrategy() { return strategy; }
        public void setStrategy(String s) { this.strategy = s; }
        public Siglip getSiglip() { return siglip; }
        public void setSiglip(Siglip s) { this.siglip = s; }

        public static class Siglip {
            private String endpoint;
            private String apiKey;
            private int dim = 1152;
            private int timeoutSeconds = 30;
            public String getEndpoint() { return endpoint; }
            public void setEndpoint(String e) { this.endpoint = e; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String k) { this.apiKey = k; }
            public int getDim() { return dim; }
            public void setDim(int d) { this.dim = d; }
            public int getTimeoutSeconds() { return timeoutSeconds; }
            public void setTimeoutSeconds(int t) { this.timeoutSeconds = t; }
        }
    }

    public static class Retrieval {
        /** Default mode used when a request omits the {@code mode} parameter. */
        private String defaultMode = "HYBRID_PLUS_RERANK";
        private int defaultTopK = 5;
        private int rerankPoolSize = 30;
        private double hybridAlpha = 0.5;
        public String getDefaultMode() { return defaultMode; }
        public void setDefaultMode(String m) { this.defaultMode = m; }
        public int getDefaultTopK() { return defaultTopK; }
        public void setDefaultTopK(int k) { this.defaultTopK = k; }
        public int getRerankPoolSize() { return rerankPoolSize; }
        public void setRerankPoolSize(int p) { this.rerankPoolSize = p; }
        public double getHybridAlpha() { return hybridAlpha; }
        public void setHybridAlpha(double a) { this.hybridAlpha = a; }
    }

    public static class Reranker {
        /** {@code cohere} (default) | {@code jina} | {@code none}. */
        private String provider = "cohere";
        private String endpoint = "https://api.cohere.com/v1/rerank";
        private String apiKey;
        private String model = "rerank-multilingual-v3.0";
        private int timeoutSeconds = 15;
        public String getProvider() { return provider; }
        public void setProvider(String p) { this.provider = p; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String e) { this.endpoint = e; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String k) { this.apiKey = k; }
        public String getModel() { return model; }
        public void setModel(String m) { this.model = m; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int t) { this.timeoutSeconds = t; }
    }

    public static class Generator {
        /** {@code claude} (default) | {@code gpt-4o} | {@code deepseek}. */
        private String provider = "claude";
        private String endpoint = "https://api.anthropic.com/v1/messages";
        private String apiKey;
        private String model = "claude-3-5-sonnet-20241022";
        private int maxTokens = 2000;
        private double temperature = 0.3;
        /** {@code llm} (image_url to LLM only) | {@code ui} (refs to UI only) | {@code both} (both paths). */
        private String imageRouting = "both";
        public String getProvider() { return provider; }
        public void setProvider(String p) { this.provider = p; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String e) { this.endpoint = e; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String k) { this.apiKey = k; }
        public String getModel() { return model; }
        public void setModel(String m) { this.model = m; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int x) { this.maxTokens = x; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double t) { this.temperature = t; }
        public String getImageRouting() { return imageRouting; }
        public void setImageRouting(String r) { this.imageRouting = r; }
    }

    public static class Captioning {
        /** {@code claude} (default) | {@code gpt-4o} | {@code qwen-vl}. */
        private String provider = "claude";
        /** Optional override of the default prompt template (see ImageCaptionService). */
        private String promptTemplate;
        public String getProvider() { return provider; }
        public void setProvider(String p) { this.provider = p; }
        public String getPromptTemplate() { return promptTemplate; }
        public void setPromptTemplate(String t) { this.promptTemplate = t; }
    }

    public static class Tracer {
        private boolean enabled = false;
        private String outputPath = "./logs/ragas-trace.jsonl";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getOutputPath() { return outputPath; }
        public void setOutputPath(String p) { this.outputPath = p; }
    }

    // ---- root getters / setters ---------------------------------------

    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding e) { this.embedding = e; }
    public Retrieval getRetrieval() { return retrieval; }
    public void setRetrieval(Retrieval r) { this.retrieval = r; }
    public Reranker getReranker() { return reranker; }
    public void setReranker(Reranker r) { this.reranker = r; }
    public Generator getGenerator() { return generator; }
    public void setGenerator(Generator g) { this.generator = g; }
    public Captioning getCaptioning() { return captioning; }
    public void setCaptioning(Captioning c) { this.captioning = c; }
    public Tracer getTracer() { return tracer; }
    public void setTracer(Tracer t) { this.tracer = t; }
}
