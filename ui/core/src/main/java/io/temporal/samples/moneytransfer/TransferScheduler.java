/*
 *  Copyright (c) 2020 Temporal Technologies, Inc. All Rights Reserved
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.samples.moneytransfer;

import static io.temporal.samples.moneytransfer.TemporalClient.getScheduleClient;
import static io.temporal.samples.moneytransfer.TemporalClient.getWorkflowServiceStubs;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.schedules.*;
import io.temporal.samples.moneytransfer.dataclasses.*;
import io.temporal.samples.moneytransfer.web.ServerInfo;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.io.FileNotFoundException;
import java.time.Duration;
import java.util.Collections;
import javax.net.ssl.SSLException;

public class TransferScheduler {

  public static TransferOutput getWorkflowOutcome(String workflowId)
      throws FileNotFoundException, SSLException {

    WorkflowClient client = TemporalClient.get();

    WorkflowStub workflowStub = client.newUntypedWorkflowStub(workflowId);

    // Returns the result after waiting for the Workflow to complete.
    TransferOutput result = workflowStub.getResult(TransferOutput.class);
    return result;
  }

  public static StateObj runQuery(String workflowId) throws FileNotFoundException, SSLException {

    WorkflowClient client = TemporalClient.get();

    // print workflow ID
    System.out.println("Workflow STATUS: " + getWorkflowStatus(workflowId));

    WorkflowStub workflowStub = client.newUntypedWorkflowStub(workflowId);

    StateObj result = workflowStub.query("transferStatus", StateObj.class);

    if ("WORKFLOW_EXECUTION_STATUS_FAILED".equals(getWorkflowStatus(workflowId))) {
      result.setWorkflowStatus("FAILED");
    }

    return result;
  }

  public static String runWorkflow(TransferInput workflowParameterObj)
      throws FileNotFoundException, SSLException {
    // generate a random reference number
    String referenceNumber = generateReferenceNumber(); // random reference number

    // Workflow execution code

    WorkflowClient client = TemporalClient.get();
    final String TASK_QUEUE = ServerInfo.getTaskqueue();

    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(referenceNumber)
            .setTaskQueue(TASK_QUEUE)
            .build();
    AccountTransferWorkflow transferWorkflow =
        client.newWorkflowStub(AccountTransferWorkflow.class, options);

    WorkflowClient.start(transferWorkflow::transfer, workflowParameterObj);
    System.out.printf("\n\nTransfer of $%d requested\n", workflowParameterObj.getAmount());

    return referenceNumber;
  }

  public static String runSchedule(ScheduleParameterObj scheduleParameterObj) {

    String scheduleNumber = null;
    try {
      int amountCents = scheduleParameterObj.getAmount(); // amount to transfer

      TransferInput params = new TransferInput(amountCents, "account1", "account2");

      ScheduleClient scheduleClient = getScheduleClient();

      String referenceNumber = generateReferenceNumber(); // random reference number
      scheduleNumber = referenceNumber + "-schedule";
      final String TASK_QUEUE = ServerInfo.getTaskqueue();

      WorkflowOptions options =
          WorkflowOptions.newBuilder()
              .setWorkflowId(referenceNumber)
              .setTaskQueue(TASK_QUEUE)
              .build();

      ScheduleActionStartWorkflow action =
          ScheduleActionStartWorkflow.newBuilder()
              .setWorkflowType(AccountTransferWorkflow.class)
              .setArguments(params)
              .setOptions(options)
              .build();

      // Define the schedule we want to create
      Schedule schedule =
          Schedule.newBuilder()
              .setAction(action)
              .setSpec(ScheduleSpec.newBuilder().build())
              .build();

      ScheduleHandle handle =
          scheduleClient.createSchedule(
              scheduleNumber, schedule, ScheduleOptions.newBuilder().build());

      // Update the schedule with a spec, so it will run periodically
      handle.update(
          (ScheduleUpdateInput input) -> {
            Schedule.Builder builder = Schedule.newBuilder(input.getDescription().getSchedule());

            builder.setSpec(
                ScheduleSpec.newBuilder()
                    .setIntervals(
                        Collections.singletonList(
                            new ScheduleIntervalSpec(
                                Duration.ofSeconds(scheduleParameterObj.getInterval()))))
                    .build());
            // Make the schedule paused to demonstrate how to unpause a schedule
            builder.setState(
                ScheduleState.newBuilder()
                    .setLimitedAction(true)
                    .setRemainingActions(scheduleParameterObj.getCount())
                    .build());

            // Temporal's default schedule policy is 'skip'
            builder.setPolicy(
                SchedulePolicy.newBuilder()
                    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                    .build());

            return new ScheduleUpdate(builder.build());
          });

      // Unpause schedule
      //      handle.unpause();
    } catch (Exception e) {
      System.out.println("Exception: " + e);
    }
    return scheduleNumber;
  }

  @SuppressWarnings("CatchAndPrintStackTrace")
  public static void main(String[] args) throws Exception {

    int amountCents = 45; // amount to transfer

    TransferInput params = new TransferInput(amountCents, "account1", "account2");

    runWorkflow(params);

    System.exit(0);
  }

  private static String generateReferenceNumber() {
    return String.format(
        "TRANSFER-%s-%03d",
        (char) (Math.random() * 26 + 'A')
            + ""
            + (char) (Math.random() * 26 + 'A')
            + ""
            + (char) (Math.random() * 26 + 'A'),
        (int) (Math.random() * 999));
  }

  private static String getWorkflowStatus(String workflowId)
      throws FileNotFoundException, SSLException {
    WorkflowServiceStubs service = getWorkflowServiceStubs();
    WorkflowServiceGrpc.WorkflowServiceBlockingStub stub = service.blockingStub();
    DescribeWorkflowExecutionRequest request =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(ServerInfo.getNamespace())
            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
            .build();
    DescribeWorkflowExecutionResponse response = stub.describeWorkflowExecution(request);
    return response.getWorkflowExecutionInfo().getStatus().name();
  }
}
