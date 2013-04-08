package parquet.avro;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericArray;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import parquet.hadoop.api.WriteSupport;
import parquet.io.api.Binary;
import parquet.io.api.RecordConsumer;
import parquet.schema.GroupType;
import parquet.schema.MessageType;
import parquet.schema.Type;

/**
 * Avro implementation of {@link WriteSupport} for {@link GenericRecord}s. Users should
 * use {@link AvroParquetWriter} or {@link AvroParquetOutputFormat} rather than using
 * this class directly.
 */
public class AvroWriteSupport extends WriteSupport<GenericRecord> {

  private RecordConsumer recordConsumer;
  private MessageType rootSchema;
  private Schema rootAvroSchema;

  public AvroWriteSupport() {
  }

  public AvroWriteSupport(MessageType schema, Schema avroSchema) {
    this.rootSchema = schema;
    this.rootAvroSchema = avroSchema;
  }

  public static void setSchema(Configuration configuration, Schema schema) {
    configuration.set("parquet.avro.schema", schema.toString());
  }

  @Override
  public WriteContext init(Configuration configuration) {
    if (rootAvroSchema == null) {
      rootAvroSchema = new Schema.Parser().parse(configuration.get("parquet.avro.schema"));
      rootSchema = new AvroSchemaConverter().convert(rootAvroSchema);
    }
    Map<String, String> extraMetaData = new HashMap<String, String>();
    extraMetaData.put("avro.schema", rootAvroSchema.toString());
    return new WriteContext(rootSchema, extraMetaData);
  }

  @Override
  public void prepareForWrite(RecordConsumer recordConsumer) {
    this.recordConsumer = recordConsumer;
  }

  @Override
  public void write(GenericRecord record) {
    recordConsumer.startMessage();
    writeRecordFields(rootSchema, rootAvroSchema, record);
    recordConsumer.endMessage();
  }

  private void writeRecord(GroupType schema, Schema avroSchema,
      GenericRecord record) {
    recordConsumer.startGroup();
    writeRecordFields(schema, avroSchema, record);
    recordConsumer.endGroup();
  }

  private void writeRecordFields(GroupType schema, Schema avroSchema,
      GenericRecord record) {
    List<Type> fields = schema.getFields();
    List<Schema.Field> avroFields = avroSchema.getFields();
    int index = 0; // parquet ignores Avro nulls, so index may differ
    for (int avroIndex = 0; avroIndex < avroFields.size(); avroIndex++) {
      Schema.Field avroField = avroFields.get(avroIndex);
      if (avroField.schema().getType().equals(Schema.Type.NULL)) {
        continue;
      }
      Type fieldType = fields.get(index);
      recordConsumer.startField(fieldType.getName(), index);
      writeValue(fieldType, avroField.schema(), record.get(avroIndex));
      recordConsumer.endField(fieldType.getName(), index);
      index++;
    }
  }

  private void writeArray(GroupType schema, Schema avroSchema, GenericArray array) {
    GroupType innerGroup = schema.getType(0).asGroupType();
    Type elementType = innerGroup.getType(0);

    recordConsumer.startGroup(); // group wrapper (original type LIST)
    recordConsumer.startField("array", 0);
    recordConsumer.startGroup(); // "repeated" group wrapper
    recordConsumer.startField("item", 0);
    for (Object elt : array) {
      writeValue(elementType, avroSchema.getElementType(), elt);
    }
    recordConsumer.endField("item", 0);
    recordConsumer.endGroup();
    recordConsumer.endField("array", 0);
    recordConsumer.endGroup();
  }

  private <String, Object> void writeMap(GroupType schema, Schema avroSchema, Map<String, Object> map) {
    GroupType innerGroup = schema.getType(0).asGroupType();
    Type keyType = innerGroup.getType(0);
    Type valueType = innerGroup.getType(1);
    Schema keySchema = Schema.create(Schema.Type.STRING);

    recordConsumer.startGroup(); // group wrapper (original type MAP)
    recordConsumer.startField("map", 0);
    recordConsumer.startGroup(); // "repeated" group wrapper
    recordConsumer.startField("key", 0);
    for (String key : map.keySet()) {
      writeValue(keyType, keySchema, key);
    }
    recordConsumer.endField("key", 0);
    recordConsumer.startField("value", 1);
    for (Object value : map.values()) {
      writeValue(valueType, avroSchema.getValueType(), value);
    }
    recordConsumer.endField("value", 1);
    recordConsumer.endGroup();
    recordConsumer.endField("map", 0);
    recordConsumer.endGroup();
  }

  private void writeValue(Type type, Schema avroSchema, Object value) {
    Schema.Type avroType = avroSchema.getType();
    if (avroType.equals(Schema.Type.BOOLEAN)) {
      recordConsumer.addBoolean((Boolean) value);
    } else if (avroType.equals(Schema.Type.INT)) {
      recordConsumer.addInteger(((Number) value).intValue());
    } else if (avroType.equals(Schema.Type.LONG)) {
      recordConsumer.addLong(((Number) value).longValue());
    } else if (avroType.equals(Schema.Type.FLOAT)) {
      recordConsumer.addFloat(((Number) value).floatValue());
    } else if (avroType.equals(Schema.Type.DOUBLE)) {
      recordConsumer.addDouble(((Number) value).doubleValue());
    } else if (avroType.equals(Schema.Type.BYTES)) {
      ByteBuffer buffer = (ByteBuffer) value;
      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      recordConsumer.addBinary(Binary.fromByteArray(bytes));
    } else if (avroType.equals(Schema.Type.STRING)) {
      recordConsumer.addBinary(Binary.fromString(value.toString())); // Utf8 or String
    } else if (avroType.equals(Schema.Type.RECORD)) {
      writeRecord((GroupType) type, avroSchema, (GenericRecord) value);
    } else if (avroType.equals(Schema.Type.ENUM)) {
      recordConsumer.addBinary(Binary.fromString(value.toString()));
    } else if (avroType.equals(Schema.Type.ARRAY)) {
      writeArray((GroupType) type, avroSchema, (GenericArray) value);
    } else if (avroType.equals(Schema.Type.MAP)) {
      writeMap((GroupType) type, avroSchema, (Map) value);
    } else if (avroType.equals(Schema.Type.FIXED)) {
      recordConsumer.addBinary(Binary.fromByteArray(((GenericFixed) value).bytes()));
    }
  }

}
