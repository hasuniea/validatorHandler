/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wso2.handler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.jayway.jsonpath.JsonPath;
import org.apache.axiom.om.OMElement;
import org.apache.axis2.AxisFault;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.Entry;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.AbstractHandler;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONException;
import org.json.JSONObject;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.utils.GatewayUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Iterator;
import java.util.Arrays;


/**
 * This com.wso2.handler.SchemaValidator handler validates the request/response messages against schema defined in the swagger.
 */
public class SchemaValidator extends AbstractHandler {

    private static final Log logger = LogFactory.getLog(SchemaValidator.class);
    private String swagger = null;
    private JsonNode rootNode;
    private String requestMethod;
    private String schemaContent = null;
    private String apiUUID;
    private String apiId;
    Entry localEntryObj = null;

    public String getApiUUID() {
        return apiUUID;
    }

    public void setApiUUID(String apiUUID) {
        this.apiUUID = apiUUID;
    }

    /**
     * Handle the API request message validation.
     *
     * @param messageContext Context of the message to be validated
     * @return Continue true the handler chain
     */
    @Override
    public boolean handleRequest(MessageContext messageContext) {
        logger.debug("Validating the API request Body content..");
        org.apache.axis2.context.MessageContext axis2MC = ((Axis2MessageContext)
                messageContext).getAxis2MessageContext();
        if (apiUUID != null) {
            localEntryObj = (Entry) messageContext.getConfiguration().getLocalRegistry().get(apiUUID);
        }
        if (localEntryObj != null) {
            swagger = localEntryObj.getValue().toString();
        }

        String contentType;
        Object objContentType = axis2MC.getProperty(SchemaValidatorConstant.REST_CONTENT_TYPE);
        if (objContentType == null) {
            return true;
        }
        contentType = objContentType.toString();
        if (logger.isDebugEnabled()) {
            logger.debug("Content type of the request message :" + contentType);
        }
        try {
            RelayUtils.buildMessage(axis2MC);
            logger.debug("Successfully built the request message");
            if (!SchemaValidatorConstant.APPLICATION_JSON.equals(contentType)) {
                return true;
            }
            if (swagger  == null) {
                return true;
            }
            ObjectMapper objectMapper = new ObjectMapper();
            rootNode = objectMapper.readTree(swagger.getBytes());
            requestMethod = messageContext.getProperty(SchemaValidatorConstant.
                    ELECTED_REQUEST_METHOD).toString();
            JSONObject payloadObject = getMessageContent(messageContext);
            if (!APIConstants.SupportedHTTPVerbs.GET.name().equals(requestMethod) &&
                    payloadObject != null && !SchemaValidatorConstant.EMPTY_ARRAY.equals(payloadObject)) {
                try {
                    validateRequest(messageContext);
                } catch (APIManagementException e) {
                    logger.error("Error occurred while validating the API request", e);
                }
            }
        } catch (IOException e) {
            logger.error("Error occurred while building the API request", e);
        } catch (XMLStreamException e) {
            logger.error("Error occurred while building the API request", e);
        }
        return true;
    }

    /**
     * Validate the response message.
     *
     * @param messageContext Context of the API response
     * @return Continue true the handler chain
     */
    @Override
    public boolean handleResponse(MessageContext messageContext) {
        logger.debug("Validating the API response  Body content..");
        org.apache.axis2.context.MessageContext axis2MC = ((Axis2MessageContext) messageContext).
                getAxis2MessageContext();
        try {
            RelayUtils.buildMessage(axis2MC);
            logger.info("Successfully built the response message");

        } catch (IOException e) {
            logger.error("Error occurred while building the API response", e);
        } catch (XMLStreamException e) {
            logger.error("Error occurred while validating the API response", e);
        }
        Object objectResponse = axis2MC.getProperty(SchemaValidatorConstant.REST_CONTENT_TYPE);
        if (objectResponse == null) {
            return true;
        }
        //String contentType = objectResponse.toString();
        //JSONObject payloadObject = getMessageContent(messageContext);
            try {
                validateResponse(messageContext);
            } catch (APIManagementException e) {
                logger.error("Error occurred while validating the API response", e);
            }
        return true;
    }

    /**
     * Validate the Request/response content.
     *
     * @param payloadObject  Request/response payload
     * @param schemaString   Schema which uses to validate request/response messages
     * @param messageContext Message context
     */
    private void validateContent(JSONObject payloadObject, String schemaString, MessageContext messageContext) {
        logger.debug("Validating JSON content against the schema");
        JSONObject jsonSchema = new JSONObject(schemaString);
        Schema schema = SchemaLoader.load(jsonSchema);
        if (schema == null) {
            return;
        }
        try {
            schema.validate(payloadObject);
        } catch (ValidationException e) {
            if (messageContext.isResponse()) {
                logger.error("Schema validation failed in the Response :" + e.getMessage(), e);
            } else {
                logger.error("Schema validation failed in the Request :" + e.getMessage(), e);
            }
            GatewayUtils.handleThreat(messageContext, APIMgtGatewayConstants.HTTP_SC_CODE, e.getMessage());
        }
    }

    /**
     * Validate the API Request JSON Body.
     *
     * @param messageContext Message context to be validate the request
     */
    private void validateRequest(MessageContext messageContext) throws APIManagementException {
        //extract particular schema content.
        String schema = getSchemaContent(messageContext);
        //extract the request payload.
        JSONObject payloadObject = getMessageContent(messageContext);
        if (schema != null && !SchemaValidatorConstant.EMPTY.equals(schema) &&
                payloadObject != null && !SchemaValidatorConstant.EMPTY_ARRAY.equals(payloadObject)) {
            validateContent(payloadObject, schema, messageContext);
        }
    }

    /**
     * Validate the API Response Body  which comes from the BE.
     *
     * @param messageContext Message context to be validate the response
     */
    private void validateResponse(MessageContext messageContext) throws APIManagementException {
        String responseSchema = getSchemaContent(messageContext);
        try {
            new JSONObject(responseSchema);
        } catch (JSONException ex) {
            return;
        }
        JSONObject payloadObject = getMessageContent(messageContext);
        if (responseSchema != null && !SchemaValidatorConstant.EMPTY_ARRAY.equals(responseSchema) &&
                payloadObject != null && !SchemaValidatorConstant.EMPTY_ARRAY.equals(payloadObject)) {
            validateContent(payloadObject, responseSchema, messageContext);
        }
    }

    /**
     * Get the Request/Response messageContent as a JsonObject.
     *
     * @param messageContext Message context
     * @return JsonObject which contains the request/response message content
     */
    private JSONObject getMessageContent(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MC = ((Axis2MessageContext)
                messageContext).getAxis2MessageContext();
        String requestMethod;
        if (messageContext.isResponse()) {
            requestMethod = messageContext.getProperty(SchemaValidatorConstant.HTTP_RESPONSE_METHOD).toString();
        } else {
            requestMethod = axis2MC.getProperty(SchemaValidatorConstant.HTTP_REQUEST_METHOD).toString();
        }
        JSONObject payloadObject = null;
        if (!APIConstants.SupportedHTTPVerbs.GET.name().equalsIgnoreCase(requestMethod) && messageContext.getEnvelope().
                getBody() != null) {
            Object objFirstElement = messageContext.getEnvelope().getBody().getFirstElement();
            if (objFirstElement != null) {
                OMElement xmlResponse = messageContext.getEnvelope().getBody().getFirstElement();
                try {
                    payloadObject = new JSONObject(JsonUtil.toJsonString(xmlResponse).toString());
                } catch (AxisFault axisFault) {
                    logger.error(" Error occurred while converting the String payload to Json");
                }
            }
        }
        return payloadObject;
    }

    /**
     * Extract the schema Object.
     *
     * @param refNode JSON node to be extracted
     * @return Extracted schema
     */
    private JsonNode extractSchemaObject(JsonNode refNode) {
        String[] val = refNode.toString().split("" + SchemaValidatorConstant.HASH);
        String path = val[1].replace("\\{^\"|\"}", SchemaValidatorConstant.EMPTY)
                .replaceAll(SchemaValidatorConstant.BACKWARD_SLASH, SchemaValidatorConstant.EMPTY);
        return rootNode.at(path);
    }

    /**
     * Get the relevant schema content for the  particular request/response messages.
     *
     * @param messageContext Message content
     * @return particular schema content with its schema initialization pattern(key/schema)
     */
    private String getSchemaContent(MessageContext messageContext) throws APIManagementException {
        String schemaKey;
        if (!messageContext.isResponse()) {
            schemaKey = extractSchemaFromRequest(messageContext);
        } else {
            schemaKey = extractResponse(messageContext);
        }
        return schemaKey;
    }

    /**
     * Extract the relevant schema from the request.
     *
     * @param messageContext Message Context
     * @return String Schema
     */
    private String extractSchemaFromRequest(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MC = ((Axis2MessageContext)
                messageContext).getAxis2MessageContext();

        String resourcePath = messageContext.getProperty(SchemaValidatorConstant.API_ELECTED_RESOURCE).toString();
        String requestMethod = axis2MC.getProperty(SchemaValidatorConstant.HTTP_REQUEST_METHOD).toString();
        String schema;
        String Swagger = swagger;
        String value = JsonPath.read(Swagger, SchemaValidatorConstant.JSON_PATH + ".openapi").toString();
        if (value != null && !value.equals(SchemaValidatorConstant.EMPTY_ARRAY)) {
            //refer schema
            StringBuilder jsonPath = new StringBuilder();
            jsonPath.append(SchemaValidatorConstant.PATHS)
                    .append(resourcePath).append("..requestBody.content.application/json.schema");
            schema = JsonPath.read(Swagger, jsonPath.toString()).toString();
            if (schema == null | SchemaValidatorConstant.EMPTY_ARRAY.equals(schema)) {
                // refer request bodies
                StringBuilder requestBodyPath = new StringBuilder();
                requestBodyPath.append(SchemaValidatorConstant.PATHS).append(resourcePath).
                        append(SchemaValidatorConstant.JSONPATH_SEPARATE).
                        append(requestMethod.toLowerCase()).append("..requestBody");
                schema = JsonPath.read(Swagger, requestBodyPath.toString()).toString();
            }
        } else {
            StringBuilder schemaPath = new StringBuilder();
            schemaPath.append(SchemaValidatorConstant.PATHS).append(resourcePath).
                    append(SchemaValidatorConstant.JSONPATH_SEPARATE)
                    .append(requestMethod.toLowerCase()).append(".parameters..schema");
            schema = JsonPath.read(Swagger, schemaPath.toString()).toString();
        }
        return extractReference(schema);
    }

    /**
     * Extract the response schema from swagger according to the response code.
     *
     * @param messageContext message content
     * @return response schema
     * @throws APIManagementException wrap and throw IOException
     */
    private String extractResponse(MessageContext messageContext) throws APIManagementException {
        Object resourceSchema;
        Object resource;
        String nonSchema = "";
        String value;
        org.apache.axis2.context.MessageContext axis2MC = ((Axis2MessageContext)
                messageContext).getAxis2MessageContext();
        String electedResource = messageContext.getProperty(SchemaValidatorConstant.API_ELECTED_RESOURCE).toString();
        String reqMethod = messageContext.getProperty(SchemaValidatorConstant.ELECTED_REQUEST_METHOD).toString();
        String responseStatus = axis2MC.getProperty(SchemaValidatorConstant.HTTP_SC).toString();


        StringBuilder responseSchemaPath = new StringBuilder();
        responseSchemaPath.append(SchemaValidatorConstant.PATHS).append(electedResource).
                append(SchemaValidatorConstant.JSONPATH_SEPARATE).append(reqMethod.toLowerCase()).
                append(SchemaValidatorConstant.JSON_RESPONSES).append(responseStatus);
        resource = JsonPath.read(swagger, responseSchemaPath.toString());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.convertValue(resource, JsonNode.class);
        if (jsonNode.get(0) != null && !SchemaValidatorConstant.EMPTY_ARRAY.equals(jsonNode)) {
            value = jsonNode.get(0).toString();
        } else {
            value = jsonNode.toString();
        }
        if (value == null) {
            return nonSchema;
        }
        StringBuilder resPath = new StringBuilder();
        resPath.append(SchemaValidatorConstant.PATHS).append(electedResource).append(
                SchemaValidatorConstant.JSONPATH_SEPARATE).append(reqMethod.toLowerCase()).
                append(SchemaValidatorConstant.JSON_RESPONSES).append(responseStatus).append(".schema");
        resource = JsonPath.read(swagger, resPath.toString());
        JsonNode json = mapper.convertValue(resource, JsonNode.class);
        if (json.get(0) != null && !SchemaValidatorConstant.EMPTY_ARRAY.equals(json.get(0))) {
            value = json.get(0).toString();
        } else {
            value = json.toString();
        }
        if (value != null && !SchemaValidatorConstant.EMPTY_ARRAY.equals(value)) {
            if (value.contains(SchemaValidatorConstant.SCHEMA_REFERENCE)) {
                byte[] bytes = value.getBytes();
                try {
                    JsonNode node = mapper.readTree(bytes);
                    Iterator<JsonNode> schemaNode = node.findParent(
                            SchemaValidatorConstant.SCHEMA_REFERENCE).elements();
                    return extractRef(schemaNode);
                } catch (IOException e) {
                    throw new APIManagementException("Error occurred while converting bytes from json node");
                }
            } else {
                return value;
            }
        } else {
            StringBuilder responseDefaultPath = new StringBuilder();
            responseDefaultPath.append(SchemaValidatorConstant.PATHS).append(electedResource).
                    append(SchemaValidatorConstant.JSONPATH_SEPARATE).append(reqMethod.toLowerCase()).
                    append(".responses.default");
            resourceSchema = JsonPath.read(swagger, responseDefaultPath.toString());
            JsonNode jnode = mapper.convertValue(resourceSchema, JsonNode.class);
            if (jnode.get(0) != null && !SchemaValidatorConstant.EMPTY_ARRAY.equals(jnode)) {
                value = jnode.get(0).toString();
            } else {
                value = jnode.toString();
            }
            if (resourceSchema != null) {
                if (value.contains(SchemaValidatorConstant.SCHEMA_REFERENCE)) {
                    byte[] bytes = value.getBytes();
                    try {
                        JsonNode node = mapper.readTree(bytes);
                        if (node != null) {
                            Iterator<JsonNode> schemaNode = node.findParent(
                                    SchemaValidatorConstant.SCHEMA_REFERENCE).elements();
                            return extractRef(schemaNode);
                        }
                    } catch (IOException e) {
                        logger.error("Error occurred while reading the schema.", e);
                        throw new APIManagementException(e);
                    }
                } else {
                    return value;
                }
            } else {
                return value;
            }
        }
        return nonSchema;
    }

    /**
     * Replace $ref array elements.
     *
     * @param entry Array reference to be replaced from actual value
     * @throws APIManagementException Throws
     */
    private void generateArraySchemas(Map.Entry<String, JsonNode> entry) throws APIManagementException {
        JsonNode entryRef;
        JsonNode schemaProperty;
        if (entry.getValue() != null) {
            schemaProperty = entry.getValue();
            if (schemaProperty == null) {
                return;
            }
            Iterator<JsonNode> arrayElements = schemaProperty.elements();
            while (arrayElements.hasNext()) {
                entryRef = arrayElements.next();
                if (entryRef != null) {
                    if (entryRef.has(SchemaValidatorConstant.SCHEMA_REFERENCE)) {
                        entryRef = extractSchemaObject(entryRef);
                        ObjectMapper mapper = new ObjectMapper();
                        String[] str = schemaProperty.toString().split(",");
                        if (str.length > 0) {
                            List<String> schemaItems = Arrays.asList(str);
                            ArrayList<String> convertedSchemaItems = new ArrayList(schemaItems);
                            for (int x = 0; x < convertedSchemaItems.size(); x++) {
                                String refItem = convertedSchemaItems.get(x);
                                if (refItem.contains(SchemaValidatorConstant.SCHEMA_REFERENCE)) {
                                    convertedSchemaItems.remove(refItem);
                                    convertedSchemaItems.add(entryRef.toString());
                                }
                            }
                            try {
                                JsonNode actualObj = mapper.readTree(convertedSchemaItems.toString());
                                entry.setValue(actualObj);
                            } catch (IOException e) {
                                throw new APIManagementException(
                                        "Error occurred while converting string to json elements", e);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Get Schema path from $ref.
     *
     * @param schemaNode Swagger schema content
     * @return $ref path
     */
    private String extractRef(Iterator<JsonNode> schemaNode) {
        while (schemaNode.hasNext()) {
            String nodeVal = schemaNode.next().toString();
            String[] val = nodeVal.split("" + SchemaValidatorConstant.HASH);
            if (val.length > 0) {
                String path = val[1].replaceAll("^\"|\"$", SchemaValidatorConstant.EMPTY);
                if (StringUtils.isNotEmpty(path)) {
                    int c = path.lastIndexOf(SchemaValidatorConstant.FORWARD_SLASH);
                    return path.substring(c + 1);
                }
            }
            return null;
        }
        return null;
    }

    /**
     * Extract the reference.
     *
     * @param schemaNode Schema node to be extracted
     * @return extracted schema
     */
    private String extractReference(String schemaNode) {
        String[] val = schemaNode.split("" + SchemaValidatorConstant.HASH);
        String path = val[1].replaceAll("\"|}|]|\\\\", "");
        String searchLastIndex = null;
        if (StringUtils.isNotEmpty(path)) {
            int index = path.lastIndexOf(SchemaValidatorConstant.FORWARD_SLASH);
            searchLastIndex = path.substring(index + 1);
        }
        String nodeVal = path.replaceAll("" + SchemaValidatorConstant.FORWARD_SLASH, ".");
        String name;
        Object object = JsonPath.read(swagger, SchemaValidatorConstant.JSON_PATH + nodeVal);
        ObjectMapper mapper = new ObjectMapper();
        String value;
        JsonNode jsonSchema = mapper.convertValue(object, JsonNode.class);
        if (jsonSchema.get(0) != null) {
            value = jsonSchema.get(0).toString();
        } else {
            value = jsonSchema.toString();
        }
        if (value.contains(SchemaValidatorConstant.SCHEMA_REFERENCE)) {
            StringBuilder extractRefPath = new StringBuilder();
            extractRefPath.append(SchemaValidatorConstant.JSON_PATH).append(
                    SchemaValidatorConstant.REQUESTBODY_SCHEMA).
                    append(searchLastIndex).append(".content.application/json.schema");
            String res = JsonPath.read(swagger, extractRefPath.toString()).toString();
            if (res.contains("items")) {
                StringBuilder requestSchemaPath = new StringBuilder();
                requestSchemaPath.append(SchemaValidatorConstant.JSON_PATH).
                        append(SchemaValidatorConstant.REQUESTBODY_SCHEMA).append(
                        searchLastIndex).append(".content.application/json.schema.items.$ref");
                name = JsonPath.read(swagger, requestSchemaPath.toString()).toString();
                extractReference(name);
            } else {
                StringBuilder jsonSchemaRef = new StringBuilder();
                jsonSchemaRef.append(SchemaValidatorConstant.JSON_PATH).append(
                        SchemaValidatorConstant.REQUESTBODY_SCHEMA).append(searchLastIndex).append(
                        ".content.application/json.schema.$ref");
                name = JsonPath.read(swagger, jsonSchemaRef.toString()).toString();
                if (name.contains("components/schemas")) {
                    Object componentSchema = JsonPath.read(swagger, "$..components.schemas." + searchLastIndex);
                    mapper = new ObjectMapper();
                    try {
                        JsonNode jsonNode = mapper.convertValue(componentSchema, JsonNode.class);
                        generateSchema(jsonNode);
                        if (jsonNode.get(0) != null) {
                            name = jsonNode.get(0).toString();
                        } else {
                            name = jsonNode.toString();
                        }
                    } catch (APIManagementException e) {
                        logger.error("Error occurred while generating the schema content for " +
                                "the particular request", e);
                    }
                    schemaContent = name;
                } else {
                    extractReference(name);
                }
            }
        } else {
            schemaContent = value;
            return schemaContent;
        }
        return schemaContent;
    }

    /**
     * Replace $ref references with relevant schemas and recreate the swagger definition.
     *
     * @param parent Swagger definition parent Node
     * @throws APIManagementException Throws an APIManagement exception
     */
    private void generateSchema(JsonNode parent) throws APIManagementException {
        JsonNode schemaProperty;
        Iterator<Map.Entry<String, JsonNode>> schemaNode;
        if (parent.get(0) != null) {
            schemaNode = parent.get(0).fields();
        } else {
            schemaNode = parent.fields();
        }
        while (schemaNode.hasNext()) {
            Map.Entry<String, JsonNode> entry = schemaNode.next();
            if (entry.getValue().has(SchemaValidatorConstant.SCHEMA_REFERENCE)) {
                JsonNode refNode = entry.getValue();
                Iterator<Map.Entry<String, JsonNode>> refItems = refNode.fields();
                while (refItems.hasNext()) {
                    Map.Entry<String, JsonNode> entryRef = refItems.next();
                    if (entryRef.getKey().equals(SchemaValidatorConstant.SCHEMA_REFERENCE)) {
                        JsonNode schemaObject = extractSchemaObject(entryRef.getValue());
                        if (schemaObject != null) {
                            entry.setValue(schemaObject);
                        }
                    }
                }
            }
            schemaProperty = entry.getValue();
            if (JsonNodeType.OBJECT == schemaProperty.getNodeType()) {
                generateSchema(schemaProperty);
            }
            if (JsonNodeType.ARRAY == schemaProperty.getNodeType()) {
                generateArraySchemas(entry);
            }
        }
    }
}
