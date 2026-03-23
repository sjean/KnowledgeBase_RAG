package com.example.aikb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Rag rag = new Rag();
    private final Llm llm = new Llm();
    private final Milvus milvus = new Milvus();
    private final Storage storage = new Storage();

    public Jwt getJwt() {
        return jwt;
    }

    public Rag getRag() {
        return rag;
    }

    public Llm getLlm() {
        return llm;
    }

    public Milvus getMilvus() {
        return milvus;
    }

    public Storage getStorage() {
        return storage;
    }

    public static class Jwt {
        private String secret;
        private long expirationHours = 12;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getExpirationHours() {
            return expirationHours;
        }

        public void setExpirationHours(long expirationHours) {
            this.expirationHours = expirationHours;
        }
    }

    public static class Rag {
        private int topK = 3;
        private int chunkSize = 500;
        private int chunkOverlap = 50;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }
    }

    public static class Llm {
        private String apiKey;
        private String baseUrl;
        private String modelName;
        private String embeddingModel;
        private boolean logRequests = true;
        private boolean logResponses = true;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public boolean isLogRequests() {
            return logRequests;
        }

        public void setLogRequests(boolean logRequests) {
            this.logRequests = logRequests;
        }

        public boolean isLogResponses() {
            return logResponses;
        }

        public void setLogResponses(boolean logResponses) {
            this.logResponses = logResponses;
        }
    }

    public static class Milvus {
        private String host;
        private int port = 19530;
        private String collectionName;
        private int dimension = 1024;
        private String indexType = "IVF_FLAT";
        private String metricType = "COSINE";
        private int nlist = 1024;
        private int nprobe = 10;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getCollectionName() {
            return collectionName;
        }

        public void setCollectionName(String collectionName) {
            this.collectionName = collectionName;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public String getIndexType() {
            return indexType;
        }

        public void setIndexType(String indexType) {
            this.indexType = indexType;
        }

        public String getMetricType() {
            return metricType;
        }

        public void setMetricType(String metricType) {
            this.metricType = metricType;
        }

        public int getNlist() {
            return nlist;
        }

        public void setNlist(int nlist) {
            this.nlist = nlist;
        }

        public int getNprobe() {
            return nprobe;
        }

        public void setNprobe(int nprobe) {
            this.nprobe = nprobe;
        }
    }

    public static class Storage {
        private String uploadDir = "./data/uploads";

        public String getUploadDir() {
            return uploadDir;
        }

        public void setUploadDir(String uploadDir) {
            this.uploadDir = uploadDir;
        }
    }
}
