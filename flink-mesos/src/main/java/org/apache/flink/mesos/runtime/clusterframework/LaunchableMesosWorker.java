/*
 * Licensed to the Apache Software Foundation (ASF) under one
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

package org.apache.flink.mesos.runtime.clusterframework;

import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.mesos.Utils;
import org.apache.flink.mesos.scheduler.LaunchableTask;
import org.apache.flink.mesos.util.MesosArtifactResolver;
import org.apache.flink.mesos.util.MesosArtifactServer;
import org.apache.flink.mesos.util.MesosConfiguration;
import org.apache.flink.runtime.clusterframework.ContainerSpecification;
import org.apache.flink.runtime.clusterframework.ContaineredTaskManagerParameters;
import org.apache.flink.util.Preconditions;

import com.netflix.fenzo.ConstraintEvaluator;
import com.netflix.fenzo.TaskAssignmentResult;
import com.netflix.fenzo.TaskRequest;
import com.netflix.fenzo.VMTaskFitnessCalculator;
import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;

import scala.Option;

import static org.apache.flink.mesos.Utils.range;
import static org.apache.flink.mesos.Utils.ranges;
import static org.apache.flink.mesos.Utils.scalar;
import static org.apache.flink.mesos.Utils.variable;

/**
 * Implements the launch of a Mesos worker.
 *
 * <p>Translates the abstract {@link ContainerSpecification} into a concrete
 * Mesos-specific {@link Protos.TaskInfo}.
 */
public class LaunchableMesosWorker implements LaunchableTask {

	protected static final Logger LOG = LoggerFactory.getLogger(LaunchableMesosWorker.class);
	/**
	 * The set of configuration keys to be dynamically configured with a port allocated from Mesos.
	 */
	private static final String[] TM_PORT_KEYS = {
		"taskmanager.rpc.port",
		"taskmanager.data.port"};

	private final MesosArtifactResolver resolver;
	private final ContainerSpecification containerSpec;
	private final MesosTaskManagerParameters params;
	private final Protos.TaskID taskID;
	private final Request taskRequest;
	private final MesosConfiguration mesosConfiguration;

	/**
	 * Construct a launchable Mesos worker.
	 * @param resolver The resolver for retrieving artifacts (e.g. jars, configuration)
	 * @param params the TM parameters such as memory, cpu to acquire.
	 * @param containerSpec an abstract container specification for launch time.
	 * @param taskID the taskID for this worker.
	 */
	public LaunchableMesosWorker(
			MesosArtifactResolver resolver,
			MesosTaskManagerParameters params,
			ContainerSpecification containerSpec,
			Protos.TaskID taskID,
			MesosConfiguration mesosConfiguration) {
		this.resolver = Preconditions.checkNotNull(resolver);
		this.containerSpec = Preconditions.checkNotNull(containerSpec);
		this.params = Preconditions.checkNotNull(params);
		this.taskID = Preconditions.checkNotNull(taskID);
		this.mesosConfiguration = Preconditions.checkNotNull(mesosConfiguration);

		this.taskRequest = new Request();
	}

	public Protos.TaskID taskID() {
		return taskID;
	}

	@Override
	public TaskRequest taskRequest() {
		return taskRequest;
	}

	class Request implements TaskRequest {
		private final AtomicReference<TaskRequest.AssignedResources> assignedResources = new AtomicReference<>();

		@Override
		public String getId() {
			return taskID.getValue();
		}

		@Override
		public String taskGroupName() {
			return "";
		}

		@Override
		public double getCPUs() {
			return params.cpus();
		}

		@Override
		public double getMemory() {
			return params.containeredParameters().taskManagerTotalMemoryMB();
		}

		@Override
		public double getNetworkMbps() {
			return 0.0;
		}

		@Override
		public double getDisk() {
			return 0.0;
		}

		@Override
		public int getPorts() {
			return TM_PORT_KEYS.length;
		}

		@Override
		public Map<String, NamedResourceSetRequest> getCustomNamedResources() {
			return Collections.emptyMap();
		}

		@Override
		public List<? extends ConstraintEvaluator> getHardConstraints() {
			return params.constraints();
		}

		@Override
		public List<? extends VMTaskFitnessCalculator> getSoftConstraints() {
			return null;
		}

		@Override
		public void setAssignedResources(AssignedResources assignedResources) {
			this.assignedResources.set(assignedResources);
		}

		@Override
		public AssignedResources getAssignedResources() {
			return assignedResources.get();
		}

		@Override
		public String toString() {
			return "Request{" +
				"cpus=" + getCPUs() +
				"memory=" + getMemory() +
				'}';
		}
	}

	/**
	 * Construct the TaskInfo needed to launch the worker.
	 * @param slaveId the assigned slave.
	 * @param assignment the assignment details.
	 * @return a fully-baked TaskInfo.
	 */
	@Override
	public Protos.TaskInfo launch(Protos.SlaveID slaveId, TaskAssignmentResult assignment) {

		ContaineredTaskManagerParameters tmParams = params.containeredParameters();

		final Configuration dynamicProperties = new Configuration();

		// incorporate the dynamic properties set by the template
		dynamicProperties.addAll(containerSpec.getDynamicConfiguration());

		// build a TaskInfo with assigned resources, environment variables, etc
		final Protos.TaskInfo.Builder taskInfo = Protos.TaskInfo.newBuilder()
			.setSlaveId(slaveId)
			.setTaskId(taskID)
			.setName(taskID.getValue())
			.addResources(scalar("cpus", mesosConfiguration.frameworkInfo().getRole(), assignment.getRequest().getCPUs()))
			.addResources(scalar("mem", mesosConfiguration.frameworkInfo().getRole(), assignment.getRequest().getMemory()));

		final Protos.CommandInfo.Builder cmd = taskInfo.getCommandBuilder();
		final Protos.Environment.Builder env = cmd.getEnvironmentBuilder();
		final StringBuilder jvmArgs = new StringBuilder();

		//configure task manager hostname property if hostname override property is supplied
		Option<String> taskManagerHostnameOption = params.getTaskManagerHostname();

		if (taskManagerHostnameOption.isDefined()) {
			// replace the TASK_ID pattern by the actual task id value of the Mesos task
			final String taskManagerHostname = MesosTaskManagerParameters.TASK_ID_PATTERN
				.matcher(taskManagerHostnameOption.get())
				.replaceAll(Matcher.quoteReplacement(taskID.getValue()));

			dynamicProperties.setString(ConfigConstants.TASK_MANAGER_HOSTNAME_KEY, taskManagerHostname);
		}

		// use the assigned ports for the TM
		if (assignment.getAssignedPorts().size() < TM_PORT_KEYS.length) {
			throw new IllegalArgumentException("unsufficient # of ports assigned");
		}
		for (int i = 0; i < TM_PORT_KEYS.length; i++) {
			int port = assignment.getAssignedPorts().get(i);
			String key = TM_PORT_KEYS[i];
			taskInfo.addResources(ranges("ports", mesosConfiguration.frameworkInfo().getRole(), range(port, port)));
			dynamicProperties.setInteger(key, port);
		}

		// ship additional files
		for (ContainerSpecification.Artifact artifact : containerSpec.getArtifacts()) {
			cmd.addUris(Utils.uri(resolver, artifact));
		}

		// propagate environment variables
		for (Map.Entry<String, String> entry : params.containeredParameters().taskManagerEnv().entrySet()) {
			env.addVariables(variable(entry.getKey(), entry.getValue()));
		}
		for (Map.Entry<String, String> entry : containerSpec.getEnvironmentVariables().entrySet()) {
			env.addVariables(variable(entry.getKey(), entry.getValue()));
		}

		// propagate the Mesos task ID to the TM
		env.addVariables(variable(MesosConfigKeys.ENV_FLINK_CONTAINER_ID, taskInfo.getTaskId().getValue()));

		// finalize the memory parameters
		jvmArgs.append(" -Xms").append(tmParams.taskManagerHeapSizeMB()).append("m");
		jvmArgs.append(" -Xmx").append(tmParams.taskManagerHeapSizeMB()).append("m");
		if (tmParams.taskManagerDirectMemoryLimitMB() >= 0) {
			jvmArgs.append(" -XX:MaxDirectMemorySize=").append(tmParams.taskManagerDirectMemoryLimitMB()).append("m");
		}

		// pass dynamic system properties
		jvmArgs.append(' ').append(
			ContainerSpecification.formatSystemProperties(containerSpec.getSystemProperties()));

		// finalize JVM args
		env.addVariables(variable(MesosConfigKeys.ENV_JVM_ARGS, jvmArgs.toString()));

		// populate TASK_NAME and FRAMEWORK_NAME environment variables to the TM container
		env.addVariables(variable(MesosConfigKeys.ENV_TASK_NAME, taskInfo.getTaskId().getValue()));
		env.addVariables(variable(MesosConfigKeys.ENV_FRAMEWORK_NAME, mesosConfiguration.frameworkInfo().getName()));

		// build the launch command w/ dynamic application properties
		StringBuilder launchCommand = new StringBuilder();
		if (params.bootstrapCommand().isDefined()) {
			launchCommand.append(params.bootstrapCommand().get()).append(" && ");
		}
		launchCommand
			.append(params.command())
			.append(" ")
			.append(ContainerSpecification.formatSystemProperties(dynamicProperties));
		cmd.setValue(launchCommand.toString());

		// build the container info
		Protos.ContainerInfo.Builder containerInfo = Protos.ContainerInfo.newBuilder();
		// in event that no docker image or mesos image name is specified, we must still
		// set type to MESOS
		containerInfo.setType(Protos.ContainerInfo.Type.MESOS);
		switch (params.containerType()) {
			case MESOS:
				if (params.containerImageName().isDefined()) {
					containerInfo
						.setMesos(Protos.ContainerInfo.MesosInfo.newBuilder()
							.setImage(Protos.Image.newBuilder()
								.setType(Protos.Image.Type.DOCKER)
								.setDocker(Protos.Image.Docker.newBuilder()
									.setName(params.containerImageName().get()))));
				}
				break;

			case DOCKER:
				assert(params.containerImageName().isDefined());
				containerInfo
					.setType(Protos.ContainerInfo.Type.DOCKER)
					.setDocker(Protos.ContainerInfo.DockerInfo.newBuilder()
						.setNetwork(Protos.ContainerInfo.DockerInfo.Network.HOST)
						.setImage(params.containerImageName().get()));
				break;

			default:
				throw new IllegalStateException("unsupported container type");
		}

		// add any volumes to the containerInfo
		containerInfo.addAllVolumes(params.containerVolumes());
		taskInfo.setContainer(containerInfo);

		return taskInfo.build();
	}

	@Override
	public String toString() {
		return "LaunchableMesosWorker{" +
			"taskID=" + taskID +
			"taskRequest=" + taskRequest +
			'}';
	}

	/**
	 * Configures an artifact server to serve the artifacts associated with a container specification.
	 * @param server the server to configure.
	 * @param container the container with artifacts to serve.
	 * @throws IOException if the artifacts cannot be accessed.
	 */
	static void configureArtifactServer(MesosArtifactServer server, ContainerSpecification container) throws IOException {
		// serve the artifacts associated with the container environment
		for (ContainerSpecification.Artifact artifact : container.getArtifacts()) {
			server.addPath(artifact.source, artifact.dest);
		}
	}
}
