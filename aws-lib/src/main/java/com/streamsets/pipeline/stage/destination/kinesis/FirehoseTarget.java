/**
 * Copyright 2016 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.kinesis;

import com.amazonaws.services.kinesisfirehose.AmazonKinesisFirehoseClient;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchRequest;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchResponseEntry;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchResult;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.lib.generator.DataGenerator;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactory;
import com.streamsets.pipeline.stage.lib.aws.AWSUtil;
import com.streamsets.pipeline.stage.lib.kinesis.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.streamsets.pipeline.stage.lib.kinesis.KinesisUtil.KB;
import static com.streamsets.pipeline.stage.lib.kinesis.KinesisUtil.KINESIS_CONFIG_BEAN;

public class FirehoseTarget extends BaseKinesisTarget {
  private static final Logger LOG = LoggerFactory.getLogger(FirehoseTarget.class);
  private static final int MAX_RECORDS_PER_REQUEST = 500;

  private final FirehoseConfigBean conf;

  private DataGeneratorFactory generatorFactory;
  private AmazonKinesisFirehoseClient firehoseClient;

  private long recordCounter = 0L;

  public FirehoseTarget(FirehoseConfigBean conf) {
    this.conf = conf;
  }

  @Override
  protected List<ConfigIssue> init() {
    List<ConfigIssue> issues = super.init();

    if (issues.isEmpty()) {
      conf.dataFormatConfig.init(
          getContext(),
          conf.dataFormat,
          Groups.KINESIS.name(),
          KINESIS_CONFIG_BEAN + ".dataGeneratorFormatConfig",
          issues
      );
      generatorFactory = conf.dataFormatConfig.getDataGeneratorFactory();

      firehoseClient = new AmazonKinesisFirehoseClient(AWSUtil.getCredentialsProvider(conf.awsConfig));
      firehoseClient.configureRegion(conf.region);
    }

    return issues;
  }

  @Override
  public void write(Batch batch) throws StageException {
    List<com.amazonaws.services.kinesisfirehose.model.Record> records = new ArrayList<>(MAX_RECORDS_PER_REQUEST);
    List<Record> sdcRecords = new ArrayList<>(MAX_RECORDS_PER_REQUEST);

    Iterator<Record> batchIterator = batch.getRecords();
    while (batchIterator.hasNext()) {
      Record record = batchIterator.next();
      sdcRecords.add(record);

      ByteArrayOutputStream bytes = new ByteArrayOutputStream(conf.maxRecordSize * KB);
      try {
        DataGenerator generator = generatorFactory.getGenerator(bytes);
        generator.write(record);
        generator.close();

        ByteBuffer data = ByteBuffer.wrap(bytes.toByteArray());
        com.amazonaws.services.kinesisfirehose.model.Record firehoseRecord =
            new com.amazonaws.services.kinesisfirehose.model.Record();
        firehoseRecord.setData(data);
        records.add(firehoseRecord);
        if (records.size() == MAX_RECORDS_PER_REQUEST) {
          flush(records, sdcRecords);
        }
      } catch (IOException e) {
        LOG.error(Errors.KINESIS_05.getMessage(), record, e.toString(), e);
        handleFailedRecord(record, e.toString());
      }
    }
    flush(records, sdcRecords);
  }

  private void flush(List<com.amazonaws.services.kinesisfirehose.model.Record> records, List<Record> sdcRecords)
      throws StageException {

    if (records.isEmpty()) {
      return;
    }

    PutRecordBatchRequest batchRequest = new PutRecordBatchRequest()
        .withDeliveryStreamName(conf.streamName)
        .withRecords(records);
    PutRecordBatchResult result = firehoseClient.putRecordBatch(batchRequest);
    int numFailed = result.getFailedPutCount();
    if (numFailed != 0) {
      List<PutRecordBatchResponseEntry> responses = result.getRequestResponses();
      for (int i = 0; i < responses.size(); i++) {
        PutRecordBatchResponseEntry response = responses.get(i);
        if (response.getErrorCode() != null) {
          handleFailedRecord(sdcRecords.get(i), response.getErrorMessage());
        }
      }
    }

    recordCounter += records.size();

    records.clear();
    sdcRecords.clear();
  }

  @Override
  public void destroy() {
    super.destroy();
    LOG.info("Wrote {} records.", recordCounter);
  }
}
