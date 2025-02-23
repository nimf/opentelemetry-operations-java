/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.opentelemetry.resource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Translates from OpenTelemetry Resource into Google Cloud's notion of resource. */
public class ResourceTranslator {
  private ResourceTranslator() {}

  @com.google.auto.value.AutoValue
  public abstract static class AttributeMapping {
    /** The label name used in GCP's MonitoredResource. */
    public abstract String getLabelName();
    /** The list of OTEL keys that can be used for this resource label, in priority order. */
    public abstract java.util.List<AttributeKey<?>> getOtelKeys();
    /** A fallback value to set the resource. */
    public abstract Optional<String> fallbackLiteral();

    public void fill(Resource resource, GcpResource.Builder builder) {
      for (AttributeKey<?> key : getOtelKeys()) {
        Object value = resource.getAttribute(key);
        if (value != null) {
          builder.addResourceLabels(getLabelName(), value.toString());
          return;
        }
      }
      fallbackLiteral().ifPresent(value -> builder.addResourceLabels(getLabelName(), value));
    }

    public static AttributeMapping create(String labelName, AttributeKey<?> otelKey) {
      return new AutoValue_ResourceTranslator_AttributeMapping(
          labelName, java.util.Collections.singletonList(otelKey), Optional.empty());
    }

    public static AttributeMapping create(
        String labelName, AttributeKey<?> otelKey, String fallbackLiteral) {
      return new AutoValue_ResourceTranslator_AttributeMapping(
          labelName, java.util.Collections.singletonList(otelKey), Optional.of(fallbackLiteral));
    }

    public static AttributeMapping create(
        String labelName, java.util.List<AttributeKey<?>> otelKeys) {
      return new AutoValue_ResourceTranslator_AttributeMapping(
          labelName, otelKeys, Optional.empty());
    }

    public static AttributeMapping create(
        String labelName, java.util.List<AttributeKey<?>> otelKeys, String fallbackLiteral) {
      return new AutoValue_ResourceTranslator_AttributeMapping(
          labelName, otelKeys, Optional.of(fallbackLiteral));
    }
  }

  private static List<AttributeMapping> GCE_INSTANCE_LABELS =
      Arrays.asList(
          AttributeMapping.create("zone", ResourceAttributes.CLOUD_AVAILABILITY_ZONE),
          AttributeMapping.create("instance_id", ResourceAttributes.HOST_ID));
  private static List<AttributeMapping> K8S_CONTAINER_LABELS =
      Arrays.asList(
          AttributeMapping.create(
              "location",
              Arrays.asList(
                  ResourceAttributes.CLOUD_AVAILABILITY_ZONE, ResourceAttributes.CLOUD_REGION)),
          AttributeMapping.create("cluster_name", ResourceAttributes.K8S_CLUSTER_NAME),
          AttributeMapping.create("namespace_name", ResourceAttributes.K8S_NAMESPACE_NAME),
          AttributeMapping.create("container_name", ResourceAttributes.K8S_CONTAINER_NAME),
          AttributeMapping.create("pod_name", ResourceAttributes.K8S_POD_NAME));
  private static List<AttributeMapping> AWS_EC2_INSTANCE_LABELS =
      Arrays.asList(
          AttributeMapping.create("instance_id", ResourceAttributes.HOST_ID),
          AttributeMapping.create("region", ResourceAttributes.CLOUD_AVAILABILITY_ZONE),
          AttributeMapping.create("aws_account", ResourceAttributes.CLOUD_ACCOUNT_ID));
  private static List<AttributeMapping> GOOGLE_CLOUD_APP_ENGINE_INSTANCE_LABELS =
      Arrays.asList(
          AttributeMapping.create("module_id", ResourceAttributes.FAAS_NAME),
          AttributeMapping.create("version_id", ResourceAttributes.FAAS_VERSION),
          AttributeMapping.create("instance_id", ResourceAttributes.FAAS_ID),
          AttributeMapping.create("location", ResourceAttributes.CLOUD_REGION));
  private static List<AttributeMapping> GENERIC_TASK_LABELS =
      Arrays.asList(
          AttributeMapping.create(
              "location",
              Arrays.asList(
                  ResourceAttributes.CLOUD_AVAILABILITY_ZONE, ResourceAttributes.CLOUD_REGION),
              "global"),
          AttributeMapping.create("namespace", ResourceAttributes.SERVICE_NAMESPACE, ""),
          AttributeMapping.create("job", ResourceAttributes.SERVICE_NAME, ""),
          AttributeMapping.create(
              "task_id",
              Arrays.asList(ResourceAttributes.SERVICE_INSTANCE_ID, ResourceAttributes.FAAS_ID),
              ""));

  /** Converts a Java OpenTelemetry SDK resource into a GCP resource. */
  public static GcpResource mapResource(Resource resource) {
    String platform = resource.getAttribute(ResourceAttributes.CLOUD_PLATFORM);
    if (platform == null) {
      return mapBase(resource, "generic_task", GENERIC_TASK_LABELS);
    }
    switch (platform) {
      case ResourceAttributes.CloudPlatformValues.GCP_COMPUTE_ENGINE:
        return mapBase(resource, "gce_instance", GCE_INSTANCE_LABELS);
      case ResourceAttributes.CloudPlatformValues.GCP_KUBERNETES_ENGINE:
        return mapBase(resource, "k8s_container", K8S_CONTAINER_LABELS);
      case ResourceAttributes.CloudPlatformValues.AWS_EC2:
        return mapBase(resource, "aws_ec2_instance", AWS_EC2_INSTANCE_LABELS);
      case ResourceAttributes.CloudPlatformValues.GCP_APP_ENGINE:
        return mapBase(resource, "gae_instance", GOOGLE_CLOUD_APP_ENGINE_INSTANCE_LABELS);
      default:
        return mapBase(resource, "generic_task", GENERIC_TASK_LABELS);
    }
  }

  private static GcpResource mapBase(
      Resource resource, String mrType, List<AttributeMapping> mappings) {
    GcpResource.Builder mr = GcpResource.builder();
    mr.setResourceType(mrType);
    for (AttributeMapping mapping : mappings) {
      mapping.fill(resource, mr);
    }
    return mr.build();
  }
}
