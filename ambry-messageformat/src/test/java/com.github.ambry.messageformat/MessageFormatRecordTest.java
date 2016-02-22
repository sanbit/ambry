package com.github.ambry.messageformat;

import com.github.ambry.store.StoreKey;
import com.github.ambry.store.StoreKeyFactory;
import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.Crc32;
import com.github.ambry.utils.UtilsTest;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;


public class MessageFormatRecordTest {

  @Test
  public void deserializeTest() {
    try {
      // Test Blob property V1 Record
      BlobProperties properties = new BlobProperties(1234, "id", "member", "test", true, 1234);
      ByteBuffer stream =
          ByteBuffer.allocate(MessageFormatRecord.BlobProperties_Format_V1.getBlobPropertiesRecordSize(properties));
      MessageFormatRecord.BlobProperties_Format_V1.serializeBlobPropertiesRecord(stream, properties);
      stream.flip();
      BlobProperties result = MessageFormatRecord.deserializeBlobProperties(new ByteBufferInputStream(stream));
      Assert.assertEquals(properties.getBlobSize(), result.getBlobSize());
      Assert.assertEquals(properties.getContentType(), result.getContentType());
      Assert.assertEquals(properties.getCreationTimeInMs(), result.getCreationTimeInMs());
      Assert.assertEquals(properties.getOwnerId(), result.getOwnerId());
      Assert.assertEquals(properties.getServiceId(), result.getServiceId());

      // corrupt blob property V1 record
      stream.flip();
      stream.put(10, (byte) 10);
      try {
        BlobProperties resultCorrupt = MessageFormatRecord.deserializeBlobProperties(new ByteBufferInputStream(stream));
        Assert.assertEquals(true, false);
      } catch (MessageFormatException e) {
        Assert.assertEquals(e.getErrorCode(), MessageFormatErrorCodes.Data_Corrupt);
      }

      // Test delete V1 record
      ByteBuffer deleteRecord = ByteBuffer.allocate(MessageFormatRecord.Delete_Format_V1.getDeleteRecordSize());
      MessageFormatRecord.Delete_Format_V1.serializeDeleteRecord(deleteRecord, true);
      deleteRecord.flip();
      boolean deleted = MessageFormatRecord.deserializeDeleteRecord(new ByteBufferInputStream(deleteRecord));
      Assert.assertEquals(deleted, true);

      // corrupt delete V1 record
      deleteRecord.flip();
      deleteRecord.put(10, (byte) 4);
      try {
        boolean corruptDeleted = MessageFormatRecord.deserializeDeleteRecord(new ByteBufferInputStream(deleteRecord));
        Assert.assertEquals(true, false);
      } catch (MessageFormatException e) {
        Assert.assertEquals(e.getErrorCode(), MessageFormatErrorCodes.Data_Corrupt);
      }

      // Test message header V1
      ByteBuffer header = ByteBuffer.allocate(MessageFormatRecord.MessageHeader_Format_V1.getHeaderSize());
      MessageFormatRecord.MessageHeader_Format_V1.serializeHeader(header, 1000, 10, -1, 20, 30);
      header.flip();
      MessageFormatRecord.MessageHeader_Format_V1 format = new MessageFormatRecord.MessageHeader_Format_V1(header);
      Assert.assertEquals(format.getMessageSize(), 1000);
      Assert.assertEquals(format.getBlobPropertiesRecordRelativeOffset(), 10);
      Assert.assertEquals(format.getUserMetadataRecordRelativeOffset(), 20);
      Assert.assertEquals(format.getBlobRecordRelativeOffset(), 30);

      // corrupt message header V1
      header.put(10, (byte) 1);
      format = new MessageFormatRecord.MessageHeader_Format_V1(header);
      try {
        format.verifyHeader();
        Assert.assertEquals(true, false);
      } catch (MessageFormatException e) {
        Assert.assertEquals(e.getErrorCode(), MessageFormatErrorCodes.Data_Corrupt);
      }

      // Test usermetadata V1 record
      ByteBuffer usermetadata = ByteBuffer.allocate(1000);
      new Random().nextBytes(usermetadata.array());
      ByteBuffer output =
          ByteBuffer.allocate(MessageFormatRecord.UserMetadata_Format_V1.getUserMetadataSize(usermetadata));
      MessageFormatRecord.UserMetadata_Format_V1.serializeUserMetadataRecord(output, usermetadata);
      output.flip();
      ByteBuffer bufOutput = MessageFormatRecord.deserializeUserMetadata(new ByteBufferInputStream(output));
      Assert.assertArrayEquals(usermetadata.array(), bufOutput.array());

      // corrupt usermetadata record V1
      output.flip();
      Byte currentRandomByte = output.get(10);
      output.put(10, (byte) (currentRandomByte + 1));
      try {
        MessageFormatRecord.deserializeUserMetadata(new ByteBufferInputStream(output));
        Assert.assertEquals(true, false);
      } catch (MessageFormatException e) {
        Assert.assertEquals(e.getErrorCode(), MessageFormatErrorCodes.Data_Corrupt);
      }

      // Test blob record V1
      ByteBuffer data = ByteBuffer.allocate(2000);
      new Random().nextBytes(data.array());
      long size = MessageFormatRecord.Blob_Format_V1.getBlobRecordSize(2000);
      ByteBuffer sData = ByteBuffer.allocate((int) size);
      MessageFormatRecord.Blob_Format_V1.serializePartialBlobRecord(sData, 2000);
      sData.put(data);
      Crc32 crc = new Crc32();
      crc.update(sData.array(), 0, sData.position());
      sData.putLong(crc.getValue());
      sData.flip();
      BlobOutput outputData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(sData)).getBlobOutput();
      Assert.assertEquals(outputData.getSize(), 2000);
      byte[] verify = new byte[2000];
      outputData.getStream().read(verify);
      Assert.assertArrayEquals(verify, data.array());

      // corrupt blob record V1
      sData.flip();
      currentRandomByte = sData.get(10);
      sData.put(10, (byte) (currentRandomByte + 1));
      try {
        MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(sData));
        Assert.assertEquals(true, false);
      } catch (MessageFormatException e) {
        Assert.assertEquals(e.getErrorCode(), MessageFormatErrorCodes.Data_Corrupt);
      }
    } catch (Exception e) {
      Assert.assertTrue(false);
    }
  }

  @Test
  public void testBlobRecordV2()
      throws IOException, MessageFormatException {
    // Test blob record V2 for Data Blob
    testBlobRecordV2(2000, BlobType.DataBlob);

    // Test blob record V2 for Metadata Blob
    testBlobRecordV2(2000, BlobType.MetadataBlob);
  }

  @Test
  public void testMetadataContentRecordV1()
      throws IOException, MessageFormatException {
    // Test Metadata Blob V1
    List<StoreKey> keys = getKeys(60, 5);
    ByteBuffer metadataContent = getMetadataContent(keys);
    List<StoreKey> outKeys = deserializeMetadataContent(metadataContent, new MockIdFactory());
    Assert.assertEquals("List of keys dont match", keys, outKeys);
    // no testing of corruption as we metadata content record doesn't have crc
  }

  @Test
  public void testBlobRecordWithMetadataContent()
      throws IOException, MessageFormatException {
    // Test Blob V2 with actual metadata blob V1
    // construct metadata blob
    List<StoreKey> keys = getKeys(60, 5);
    ByteBuffer metadataContent = getMetadataContent(keys);
    int metadataContentSize =
        MessageFormatRecord.Metadata_Content_Format_V1.getMetadataContentSize(keys.get(0).sizeInBytes(), keys.size());
    long blobSize = MessageFormatRecord.Blob_Format_V2.getBlobRecordSize(metadataContentSize);
    metadataContent.rewind();
    ByteBuffer blob = ByteBuffer.allocate((int) blobSize);
    BlobOutput outputData = getBlobRecordV2(metadataContentSize, BlobType.MetadataBlob, metadataContent, blob);

    Assert.assertEquals(outputData.getSize(), metadataContentSize);
    byte[] verify = new byte[metadataContentSize];
    outputData.getStream().read(verify);
    Assert.assertArrayEquals("Metadata content mismatch", metadataContent.array(), verify);

    // deserialize and check for metadata contents
    metadataContent.flip();
    List<StoreKey> outputList = deserializeMetadataContent(metadataContent, new MockIdFactory());
    Assert.assertEquals("List of keys dont match", keys, outputList);

    // test corruption cases
    blob.rewind();
    // case 1: corrupt blob record version
    byte currentRandomByte = blob.get(1);
    blob.put(1, (byte) (currentRandomByte + 1));
    try {
      MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(blob));
      Assert.fail("Failed to detect corruption of Blob record version ");
    } catch (MessageFormatException e) {
      Assert.assertEquals("Error code mismatch", MessageFormatErrorCodes.Unknown_Format_Version, e.getErrorCode());
    }

    // case 2: corrupt blob type
    blob.rewind();
    //reset previously corrupt byte
    blob.put(1, currentRandomByte);
    currentRandomByte = blob.get(2);
    blob.put(2, (byte) (currentRandomByte + 1));
    try {
      MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(blob));
      Assert.fail("Failed to detect corruption of blob type");
    } catch (MessageFormatException e) {
      Assert.assertEquals("Error code mismatch", MessageFormatErrorCodes.Data_Corrupt, e.getErrorCode());
    }

    //case 3: corrupt part of metadata content
    blob.rewind();
    //reset previously corrupt byte
    blob.put(2, currentRandomByte);
    // corrupt part of metadata content
    currentRandomByte = blob.get(50);
    blob.put(50, (byte) (currentRandomByte + 1));
    try {
      MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(blob));
      Assert.fail("Failed to detect corruption of metadata content");
    } catch (MessageFormatException e) {
      Assert.assertEquals("Error code mismatch", MessageFormatErrorCodes.Data_Corrupt, e.getErrorCode());
    }
  }

  private void testBlobRecordV2(int blobSize, BlobType blobType)
      throws IOException, MessageFormatException {

    ByteBuffer blobContent = ByteBuffer.allocate(blobSize);
    new Random().nextBytes(blobContent.array());
    int size = (int) MessageFormatRecord.Blob_Format_V2.getBlobRecordSize(blobSize);
    ByteBuffer entireBlob = ByteBuffer.allocate(size);
    BlobOutput outputData = getBlobRecordV2(blobSize, blobType, blobContent, entireBlob);
    Assert.assertEquals("Blob size mismatch", blobSize, outputData.getSize());
    byte[] verify = new byte[blobSize];
    outputData.getStream().read(verify);
    Assert.assertArrayEquals("BlobContent mismatch", blobContent.array(), verify);

    // corrupt blob record V2
    entireBlob.flip();
    byte currentRandomByte = entireBlob.get(16);
    entireBlob.put(16, (byte) (currentRandomByte + 1));
    try {
      MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(entireBlob));
      Assert.fail("Failed to detect corruption of blob record");
    } catch (MessageFormatException e) {
      Assert.assertEquals("Error code mismatch", MessageFormatErrorCodes.Data_Corrupt, e.getErrorCode());
    }
  }

  private BlobOutput getBlobRecordV2(int blobSize, BlobType blobType, ByteBuffer blobContent, ByteBuffer outputBuffer)
      throws IOException, MessageFormatException {
    MessageFormatRecord.Blob_Format_V2.serializePartialBlobRecord(outputBuffer, blobSize, blobType);
    outputBuffer.put(blobContent);
    Crc32 crc = new Crc32();
    crc.update(outputBuffer.array(), 0, outputBuffer.position());
    outputBuffer.putLong(crc.getValue());
    outputBuffer.flip();
    return MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(outputBuffer)).getBlobOutput();
  }

  private ByteBuffer getMetadataContent(List<StoreKey> keys) {
    int size =
        MessageFormatRecord.Metadata_Content_Format_V1.getMetadataContentSize(keys.get(0).sizeInBytes(), keys.size());
    ByteBuffer metadataContent = ByteBuffer.allocate(size);
    MessageFormatRecord.Metadata_Content_Format_V1.serializeMetadataContentRecord(metadataContent, keys);
    metadataContent.flip();
    return metadataContent;
  }

  private List<StoreKey> deserializeMetadataContent(ByteBuffer metadataContent, StoreKeyFactory storeKeyFactory)
      throws MessageFormatException, IOException {
    ByteBufferInputStream byteBufferInputStream = new ByteBufferInputStream(metadataContent);
    DataInputStream inputStream = new DataInputStream(byteBufferInputStream);
    short metdataContentVersion = inputStream.readShort();
    Assert.assertEquals("Metadata Content Version mismatch ", MessageFormatRecord.Metadata_Content_Version_V1,
        metdataContentVersion);
    return MessageFormatRecord.Metadata_Content_Format_V1
        .deserializeMetadataContentRecord(inputStream, storeKeyFactory);
  }

  private List<StoreKey> getKeys(int keySize, int numberOfKeys) {
    List<StoreKey> keys = new ArrayList<StoreKey>();
    for (int i = 0; i < numberOfKeys; i++) {
      MockId mockId = new MockId(UtilsTest.getRandomString(keySize));
      keys.add(mockId);
    }
    return keys;
  }
}
