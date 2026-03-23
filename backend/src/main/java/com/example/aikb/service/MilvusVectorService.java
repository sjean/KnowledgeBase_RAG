package com.example.aikb.service;

import com.example.aikb.config.AppProperties;
import com.example.aikb.dto.SourceItem;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MilvusVectorService {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorService.class);

    private final AppProperties properties;
    private MilvusServiceClient milvusClient;
    private volatile boolean documentIdFieldEnabled = true;

    public MilvusVectorService(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        milvusClient = new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(properties.getMilvus().getHost())
                        .withPort(properties.getMilvus().getPort())
                        .build()
        );
        createCollectionIfNeeded();
    }

    public void storeChunks(Long userId, Long documentId, String fileName, List<String> chunks, List<List<Float>> vectors) {
        if (chunks.isEmpty()) {
            return;
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        List<Long> userIds = new ArrayList<>();
        List<Long> documentIds = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        List<String> contents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            userIds.add(userId);
            documentIds.add(documentId);
            chunkIds.add("doc-" + documentId + "-" + i + "-" + UUID.randomUUID());
            fileNames.add(fileName);
            contents.add(chunks.get(i));
        }

        fields.add(new InsertParam.Field("user_id", userIds));
        if (documentIdFieldEnabled) {
            fields.add(new InsertParam.Field("document_id", documentIds));
        }
        fields.add(new InsertParam.Field("chunk_id", chunkIds));
        fields.add(new InsertParam.Field("file_name", fileNames));
        fields.add(new InsertParam.Field("content", contents));
        fields.add(new InsertParam.Field("embedding", vectors));

        R<MutationResult> response = milvusClient.insert(
                InsertParam.newBuilder()
                        .withCollectionName(properties.getMilvus().getCollectionName())
                        .withFields(fields)
                        .build()
        );
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus insert failed: " + response.getMessage());
        }
        milvusClient.flush(FlushParam.newBuilder()
                .withCollectionNames(List.of(properties.getMilvus().getCollectionName()))
                .build());
    }

    public List<SourceItem> search(Long userId, List<Float> queryVector, int topK, boolean admin) {
        String expr = admin ? "" : "user_id == " + userId;
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(properties.getMilvus().getCollectionName())
                .withMetricType(resolveMetricType(properties.getMilvus().getMetricType()))
                .withOutFields(List.of("chunk_id", "file_name", "content", "user_id"))
                .withTopK(topK)
                .withVectors(List.of(queryVector))
                .withVectorFieldName("embedding")
                .withExpr(expr)
                .withParams("{\"nprobe\":" + properties.getMilvus().getNprobe() + "}")
                .build();

        R<SearchResults> response = milvusClient.search(searchParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus search failed: " + response.getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<SourceItem> result = new ArrayList<>();
        List<QueryResultsWrapper.RowRecord> records = wrapper.getRowRecords(0);
        for (QueryResultsWrapper.RowRecord record : records) {
            result.add(new SourceItem(
                    String.valueOf(record.get("file_name")),
                    String.valueOf(record.get("chunk_id")),
                    String.valueOf(record.get("content"))
            ));
        }
        return result;
    }

    public void deleteDocumentChunks(Long userId, Long documentId, String fileName, boolean admin) {
        String expr = buildDeleteExpr(userId, documentId, fileName, admin);
        R<MutationResult> response = milvusClient.delete(
                DeleteParam.newBuilder()
                        .withCollectionName(properties.getMilvus().getCollectionName())
                        .withExpr(expr)
                        .build()
        );
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus delete failed: " + response.getMessage());
        }
        milvusClient.flush(FlushParam.newBuilder()
                .withCollectionNames(List.of(properties.getMilvus().getCollectionName()))
                .build());
    }

    private void createCollectionIfNeeded() {
        String collectionName = properties.getMilvus().getCollectionName();
        boolean exists = milvusClient.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collectionName).build()
        ).getData();

        if (exists) {
            CollectionSchemaInfo schemaInfo = describeCollectionSchema(collectionName);
            ensureCollectionDimension(collectionName, schemaInfo.dimension(), properties.getMilvus().getDimension());
            documentIdFieldEnabled = schemaInfo.fieldNames().contains("document_id");
            if (!documentIdFieldEnabled) {
                log.warn("Milvus collection {} does not contain document_id field. Document deletion will fall back to file_name matching.", collectionName);
            }
            milvusClient.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(collectionName).build());
            return;
        }

        int dimension = properties.getMilvus().getDimension();
        CreateCollectionParam createCollectionParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withDescription("AI knowledge base chunks")
                .withShardsNum(2)
                .addFieldType(FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.Int64)
                        .withPrimaryKey(true)
                        .withAutoID(true)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("user_id")
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("document_id")
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("chunk_id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(256)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("file_name")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(256)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("content")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(8192)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("embedding")
                        .withDataType(DataType.FloatVector)
                        .withDimension(dimension)
                        .build())
                .build();
        milvusClient.createCollection(createCollectionParam);
        milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("embedding")
                .withIndexType(resolveIndexType(properties.getMilvus().getIndexType()))
                .withMetricType(resolveMetricType(properties.getMilvus().getMetricType()))
                .withExtraParam("{\"nlist\":" + properties.getMilvus().getNlist() + "}")
                .build());
        milvusClient.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(collectionName).build());
        documentIdFieldEnabled = true;
        log.info("Milvus collection created: {}, dimension={}", collectionName, dimension);
    }

    private void ensureCollectionDimension(String collectionName, int actualDimension, int expectedDimension) {
        if (actualDimension == expectedDimension) {
            return;
        }

        log.warn("Milvus collection {} dimension mismatch: actual={}, expected={}. Recreating collection.",
                collectionName, actualDimension, expectedDimension);
        R<io.milvus.param.RpcStatus> dropResponse = milvusClient.dropCollection(
                DropCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        );
        if (dropResponse.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus drop collection failed: " + dropResponse.getMessage());
        }
        createCollectionIfNeeded();
    }

    private CollectionSchemaInfo describeCollectionSchema(String collectionName) {
        R<io.milvus.grpc.DescribeCollectionResponse> response = milvusClient.describeCollection(
                DescribeCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        );
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus describe collection failed: " + response.getMessage());
        }

        DescCollResponseWrapper wrapper = new DescCollResponseWrapper(response.getData());
        int dimension = wrapper.getFieldByName("embedding").getDimension();
        Set<String> fieldNames = response.getData().getSchema().getFieldsList().stream()
                .map(io.milvus.grpc.FieldSchema::getName)
                .collect(java.util.stream.Collectors.toSet());
        return new CollectionSchemaInfo(dimension, fieldNames);
    }

    private String buildDeleteExpr(Long userId, Long documentId, String fileName, boolean admin) {
        String documentExpr = documentIdFieldEnabled
                ? "document_id == " + documentId
                : "file_name == \"" + escapeStringValue(fileName) + "\"";
        if (admin) {
            return documentExpr;
        }
        return "user_id == " + userId + " and " + documentExpr;
    }

    private String escapeStringValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private IndexType resolveIndexType(String indexType) {
        return IndexType.valueOf(indexType);
    }

    private MetricType resolveMetricType(String metricType) {
        return MetricType.valueOf(metricType);
    }

    @PreDestroy
    public void destroy() {
        if (milvusClient != null) {
            milvusClient.close();
        }
    }

    private record CollectionSchemaInfo(int dimension, Set<String> fieldNames) {
    }
}
