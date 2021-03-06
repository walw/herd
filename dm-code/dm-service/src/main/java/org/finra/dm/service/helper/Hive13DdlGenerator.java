/*
* Copyright 2015 herd contributors
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
package org.finra.dm.service.helper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.io.Charsets;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import org.finra.dm.dao.DmDao;
import org.finra.dm.model.ObjectNotFoundException;
import org.finra.dm.model.dto.HivePartitionDto;
import org.finra.dm.model.jpa.BusinessObjectDataEntity;
import org.finra.dm.model.jpa.BusinessObjectDataStatusEntity;
import org.finra.dm.model.jpa.BusinessObjectFormatEntity;
import org.finra.dm.model.jpa.CustomDdlEntity;
import org.finra.dm.model.jpa.FileTypeEntity;
import org.finra.dm.model.jpa.StorageEntity;
import org.finra.dm.model.jpa.StorageFileEntity;
import org.finra.dm.model.jpa.StorageUnitEntity;
import org.finra.dm.model.api.xml.BusinessObjectDataDdlOutputFormatEnum;
import org.finra.dm.model.api.xml.BusinessObjectDataDdlRequest;
import org.finra.dm.model.api.xml.BusinessObjectDataKey;
import org.finra.dm.model.api.xml.BusinessObjectFormat;
import org.finra.dm.model.api.xml.BusinessObjectFormatDdlRequest;
import org.finra.dm.model.api.xml.BusinessObjectFormatKey;
import org.finra.dm.model.api.xml.SchemaColumn;

/**
 * The DDL generator for Hive 13.
 */
@Component
@SuppressFBWarnings(value = "VA_FORMAT_STRING_USES_NEWLINE", justification = "We will use the standard carriage return character.")
public class Hive13DdlGenerator extends DdlGenerator
{
    @Autowired
    private DmHelper dmHelper;

    @Autowired
    private DmDao dmDao;

    @Autowired
    private DmDaoHelper dmDaoHelper;

    @Autowired
    private BusinessObjectFormatHelper businessObjectFormatHelper;

    @Autowired
    private BusinessObjectDataHelper businessObjectDataHelper;

    /**
     * Hive file format for text files.
     */
    public static final String TEXT_HIVE_FILE_FORMAT = "TEXTFILE";

    /**
     * Hive file format for PARQUET files.
     */
    public static final String PARQUET_HIVE_FILE_FORMAT = "PARQUET";

    /**
     * Hive file format for ORC files.
     */
    public static final String ORC_HIVE_FILE_FORMAT = "ORC";

    /**
     * The partition key value for business object data without partitioning.
     */
    public static final String NO_PARTITIONING_PARTITION_KEY = "partition";

    /**
     * The partition value for business object data without partitioning.
     */
    public static final String NO_PARTITIONING_PARTITION_VALUE = "none";

    @Override
    public BusinessObjectDataDdlOutputFormatEnum getDdlOutputFormat()
    {
        return BusinessObjectDataDdlOutputFormatEnum.HIVE_13_DDL;
    }

    /**
     * Generates the create table Hive 13 DDL as per specified business object format DDL request.
     *
     * @param request the business object format DDL request
     * @param businessObjectFormatEntity the business object format entity
     * @param customDdlEntity the optional custom DDL entity
     *
     * @return the create table Hive DDL
     */
    @Override
    public String generateCreateTableDdl(BusinessObjectFormatDdlRequest request, BusinessObjectFormatEntity businessObjectFormatEntity,
        CustomDdlEntity customDdlEntity)
    {
        // If the partitionKey="partition", then DDL should return a DDL which treats business object data as a table, not a partition.
        Boolean isPartitioned = !businessObjectFormatEntity.getPartitionKey().equalsIgnoreCase(NO_PARTITIONING_PARTITION_KEY);

        // Generate the create table Hive 13 DDL.
        return generateCreateTableDdlHelper(businessObjectFormatEntity, customDdlEntity, isPartitioned, request.getTableName(),
            request.isIncludeDropTableStatement(), request.isIncludeIfNotExistsOption(), null, null, null, null, null, null);
    }

    /**
     * Generates the create table Hive 13 DDL as per specified business object data DDL request.
     *
     * @param request the business object data DDL request
     * @param businessObjectFormatEntity the business object format entity
     * @param customDdlEntity the optional custom DDL entity
     * @param storageEntity the storage entity
     * @param s3BucketName the S3 bucket name
     *
     * @return the create table Hive DDL
     */
    @Override
    public String generateCreateTableDdl(BusinessObjectDataDdlRequest request, BusinessObjectFormatEntity businessObjectFormatEntity,
        CustomDdlEntity customDdlEntity, StorageEntity storageEntity, String s3BucketName)
    {
        // Get business object format key from the request.
        BusinessObjectFormatKey businessObjectFormatKey =
            new BusinessObjectFormatKey(request.getNamespace(), request.getBusinessObjectDefinitionName(), request.getBusinessObjectFormatUsage(),
                request.getBusinessObjectFormatFileType(), request.getBusinessObjectFormatVersion());

        // Build partition filters based on the specified partition value filters.
        List<List<String>> partitionFilters = businessObjectDataHelper
            .buildPartitionFilters(request.getPartitionValueFilters(), request.getPartitionValueFilter(), businessObjectFormatKey,
                request.getBusinessObjectDataVersion(), request.getStorageName(), businessObjectFormatEntity);

        // If the partitionKey="partition" and partitionValue="none", then DDL should
        // return a DDL which treats business object data as a table, not a partition.
        boolean isPartitioned = !businessObjectFormatEntity.getPartitionKey().equalsIgnoreCase(NO_PARTITIONING_PARTITION_KEY) ||
            partitionFilters.size() != 1 ||
            !partitionFilters.get(0).get(0).equalsIgnoreCase(NO_PARTITIONING_PARTITION_VALUE);

        // Generate the create table Hive 13 DDL.
        return generateCreateTableDdlHelper(businessObjectFormatEntity, customDdlEntity, isPartitioned, request.getTableName(),
            request.isIncludeDropTableStatement(), request.isIncludeIfNotExistsOption(), request.isAllowMissingData(), partitionFilters,
            request.getBusinessObjectFormatVersion(), request.getBusinessObjectDataVersion(), storageEntity, s3BucketName);
    }

    /**
     * Generates and append to the string builder the create table Hive 13 DDL as per specified parameters.
     */
    private String generateCreateTableDdlHelper(BusinessObjectFormatEntity businessObjectFormatEntity, CustomDdlEntity customDdlEntity, Boolean isPartitioned,
        String tableName, Boolean includeDropTableStatement, Boolean includeIfNotExistsOption, Boolean allowMissingData, List<List<String>> partitionFilters,
        Integer businessObjectFormatVersion, Integer businessObjectDataVersion, StorageEntity storageEntity, String s3BucketName)
    {
        // TODO: We might want to consider using a template engine such as Velocity to generate this DDL so we don't wind up just doing string manipulation.

        StringBuilder sb = new StringBuilder();

        // For custom DDL, we would need to substitute the custom DDL tokens with their relative values.
        HashMap<String, String> replacements = new HashMap<>();

        // Validate that partition values passed in the list of partition filters do not contain '/' character.
        if (isPartitioned && !CollectionUtils.isEmpty(partitionFilters))
        {
            // Validate that partition values do not contain '/' characters.
            for (List<String> partitionFilter : partitionFilters)
            {
                for (String partitionValue : partitionFilter)
                {
                    Assert.doesNotContain(partitionValue, "/", String.format("Partition value \"%s\" can not contain a '/' character.", partitionValue));
                }
            }
        }

        // Get business object format model object to directly access schema columns and partitions.
        BusinessObjectFormat businessObjectFormat = businessObjectFormatHelper.createBusinessObjectFormatFromEntity(businessObjectFormatEntity);

        // Validate that we have at least one column specified in the business object format schema.
        Assert.notEmpty(businessObjectFormat.getSchema().getColumns(), String.format("No schema columns specified for business object format {%s}.",
            dmDaoHelper.businessObjectFormatEntityAltKeyToString(businessObjectFormatEntity)));

        if (isPartitioned)
        {
            // Validate that we have at least one partition column specified in the business object format schema.
            Assert.notEmpty(businessObjectFormat.getSchema().getPartitions(), String.format("No schema partitions specified for business object format {%s}.",
                dmDaoHelper.businessObjectFormatEntityAltKeyToString(businessObjectFormatEntity)));

            // Validate that partition column names do not contain '/' characters.
            for (SchemaColumn partitionColumn : businessObjectFormat.getSchema().getPartitions())
            {
                Assert.doesNotContain(partitionColumn.getName(), "/", String
                    .format("Partition column name \"%s\" can not contain a '/' character. Business object format: {%s}", partitionColumn.getName(),
                        dmDaoHelper.businessObjectFormatEntityAltKeyToString(businessObjectFormatEntity)));
            }
        }

        // Add drop table if requested.
        if (includeDropTableStatement != null && includeDropTableStatement)
        {
            sb.append(String.format("DROP TABLE IF EXISTS `%s`;\n\n", tableName));
        }

        // Depending on the flag, prepare "if not exists" option text or leave it an empty string.
        String ifNotExistsOption = includeIfNotExistsOption != null && includeIfNotExistsOption ? "IF NOT EXISTS " : "";

        // Only generate the create table DDL statement, if custom DDL was not specified.
        if (customDdlEntity == null)
        {
            generateStandardBaseDdl(businessObjectFormatEntity, isPartitioned, tableName, sb, businessObjectFormat, ifNotExistsOption);
        }
        else
        {
            // Use the custom DDL in place of the create table statement.
            sb.append(String.format("%s\n\n", customDdlEntity.getDdl()));

            // We need to substitute the relative custom DDL token with an actual table name.
            replacements.put(TABLE_NAME_CUSTOM_DDL_TOKEN, tableName);
        }

        // Add alter table statements only if the list of partition filters is not empty - this is applicable to generating DDL for business object data only.
        if (!CollectionUtils.isEmpty(partitionFilters))
        {
            processPartitionFiltersForGenerateDdl(businessObjectFormatEntity, customDdlEntity, isPartitioned, tableName, allowMissingData, partitionFilters,
                businessObjectFormatVersion, businessObjectDataVersion, storageEntity, s3BucketName, sb, replacements, businessObjectFormat, ifNotExistsOption);
        }
        // Add a location statement with a token if this is format dll that does not use custom ddl.
        else if (!isPartitioned && customDdlEntity == null)
        {
            // Since custom DDL is not specified, there are no partition values, and this table is not partitioned, add a LOCATION clause with a token.
            sb.append(String.format("LOCATION '%s';", NON_PARTITIONED_TABLE_LOCATION_CUSTOM_DDL_TOKEN));
        }

        // Trim to remove unnecessary end-of-line characters, if any, from the end of the generated DDL.
        String resultDdl = sb.toString().trim();

        // For custom DDL, substitute the relative custom DDL tokens with their values.
        if (customDdlEntity != null)
        {
            for (Map.Entry<String, String> entry : replacements.entrySet())
            {
                String token = entry.getKey();
                String value = entry.getValue();
                resultDdl = resultDdl.replaceAll(Pattern.quote(token), value);
            }
        }

        return resultDdl;
    }

    private void generateStandardBaseDdl(BusinessObjectFormatEntity businessObjectFormatEntity, Boolean isPartitioned, String tableName, StringBuilder sb,
        BusinessObjectFormat businessObjectFormat, String ifNotExistsOption)
    {
        // Please note that we escape table name and all column names to avoid Hive reserved words in DDL statement generation.
        sb.append(String.format("CREATE EXTERNAL TABLE %s`%s` (\n", ifNotExistsOption, tableName));

        // Add schema columns.
        Boolean firstRow = true;
        for (SchemaColumn schemaColumn : businessObjectFormat.getSchema().getColumns())
        {
            if (!firstRow)
            {
                sb.append(",\n");
            }
            else
            {
                firstRow = false;
            }

            // Add a schema column declaration. Check if a schema column is also a partition column and prepend "ORGNL_" prefix if this is the case.
            sb.append(String.format("    `%s%s` %s%s", (!CollectionUtils.isEmpty(businessObjectFormat.getSchema().getPartitions()) &&
                businessObjectFormat.getSchema().getPartitions().contains(schemaColumn) ? "ORGNL_" : ""), schemaColumn.getName(),
                getHiveDataType(schemaColumn, businessObjectFormatEntity),
                StringUtils.isNotBlank(schemaColumn.getDescription()) ? String.format(" COMMENT '%s'", escapeSingleQuotes(schemaColumn.getDescription())) :
                    ""));
        }
        sb.append(")\n");

        if (isPartitioned)
        {
            // Add a partitioned by clause.
            sb.append("PARTITIONED BY (");
            // List all partition columns.
            List<String> partitionColumnDeclarations = new ArrayList<>();
            for (SchemaColumn partitionColumn : businessObjectFormat.getSchema().getPartitions())
            {
                partitionColumnDeclarations
                    .add(String.format("`%s` %s", partitionColumn.getName(), getHiveDataType(partitionColumn, businessObjectFormatEntity)));
            }
            sb.append(StringUtils.join(partitionColumnDeclarations, ", "));
            sb.append(")\n");
        }

        // We output delimiter character, escape character, and null value only when they are defined in the business object format schema.
        sb.append("ROW FORMAT DELIMITED");
        if (!StringUtils.isEmpty(businessObjectFormatEntity.getDelimiter()))
        {
            // Note that the escape character is only output when the delimiter is present.
            sb.append(String.format(" FIELDS TERMINATED BY '%s'%s", escapeSingleQuotes(getDdlCharacterValue(businessObjectFormatEntity.getDelimiter(), true)),
                StringUtils.isEmpty(businessObjectFormatEntity.getEscapeCharacter()) ? "" :
                    String.format(" ESCAPED BY '%s'", escapeSingleQuotes(getDdlCharacterValue(businessObjectFormatEntity.getEscapeCharacter(), true)))));
        }
        sb.append(String.format(" NULL DEFINED AS '%s'\n", escapeSingleQuotes(getDdlCharacterValue(businessObjectFormatEntity.getNullValue()))));

        // If this table is not partitioned, then STORED AS clause will be followed by LOCATION. Otherwise, the CREATE TABLE is complete.
        sb.append(String.format("STORED AS %s%s\n", getHiveFileFormat(businessObjectFormatEntity), isPartitioned ? ";\n" : ""));
    }

    private void processPartitionFiltersForGenerateDdl(BusinessObjectFormatEntity businessObjectFormatEntity, CustomDdlEntity customDdlEntity,
        Boolean isPartitioned, String tableName, Boolean allowMissingData, List<List<String>> partitionFilters, Integer businessObjectFormatVersion,
        Integer businessObjectDataVersion, StorageEntity storageEntity, String s3BucketName, StringBuilder sb, HashMap<String, String> replacements,
        BusinessObjectFormat businessObjectFormat, String ifNotExistsOption)
    {
        // Get the business object format key from the entity.
        BusinessObjectFormatKey businessObjectFormatKey = dmDaoHelper.getBusinessObjectFormatKey(businessObjectFormatEntity);

        // Override the business object format version with the original (optional) value from the request.
        businessObjectFormatKey.setBusinessObjectFormatVersion(businessObjectFormatVersion);

        // Retrieve a list of business object data entities for the specified list of partition filters. The entities will be sorted by partition values.
        // Please note that for a non-partitioned table, we would only get a single business object data entity (with partitionValue equals to "none").
        List<BusinessObjectDataEntity> businessObjectDataEntities = dmDao
            .getBusinessObjectDataEntities(businessObjectFormatKey, partitionFilters, businessObjectDataVersion, BusinessObjectDataStatusEntity.VALID,
                storageEntity.getName());

        // Build a list of matched partition filters. Please note that each request partition
        // filter might result in multiple available business object data entities.
        List<List<String>> matchedPartitionFilters = new ArrayList<>();
        for (BusinessObjectDataEntity businessObjectDataEntity : businessObjectDataEntities)
        {
            matchedPartitionFilters.add(dmDaoHelper.getPartitionFilter(businessObjectDataEntity, partitionFilters.get(0)));
        }

        // Fail on any missing business object data unless the flag is set.
        if (allowMissingData == null || !allowMissingData)
        {
            // Get a list of unmatched partition filters.
            List<List<String>> unmatchedPartitionFilters = new ArrayList<>(partitionFilters);
            unmatchedPartitionFilters.removeAll(matchedPartitionFilters);

            // Throw an exception if we have any unmatched partition filters.
            if (!unmatchedPartitionFilters.isEmpty())
            {
                List<String> unmatchedPartitionFilter = unmatchedPartitionFilters.get(0);
                // Replace all null partition values with an empty string.
                for (int i = 0; i < unmatchedPartitionFilter.size(); i++)
                {
                    if (unmatchedPartitionFilter.get(i) == null)
                    {
                        unmatchedPartitionFilter.set(i, "");
                    }
                }
                throw new ObjectNotFoundException(
                    String.format("Business object data {namespace: \"%s\", businessObjectDefinitionName: \"%s\", businessObjectFormatUsage: \"%s\", " +
                        "businessObjectFormatFileType: \"%s\", businessObjectFormatVersion: %d, partitionValue: \"%s\", " +
                        "subpartitionValues: \"%s\", businessObjectDataVersion: %d} is not available in \"%s\" storage.",
                        businessObjectFormatKey.getNamespace(), businessObjectFormatKey.getBusinessObjectDefinitionName(),
                        businessObjectFormatKey.getBusinessObjectFormatUsage(), businessObjectFormatKey.getBusinessObjectFormatFileType(),
                        businessObjectFormatKey.getBusinessObjectFormatVersion(), unmatchedPartitionFilter.get(0),
                        StringUtils.join(unmatchedPartitionFilter.subList(1, unmatchedPartitionFilter.size()), ","), businessObjectDataVersion,
                        storageEntity.getName()));
            }
        }

        // We still need to close/complete the create table statement when there is no custom DDL,
        // the table is non-partitioned, and there is no business object data found.
        if (customDdlEntity == null && !isPartitioned && CollectionUtils.isEmpty(businessObjectDataEntities))
        {
            // Add a LOCATION clause with a token.
            sb.append(String.format("LOCATION '%s';", NON_PARTITIONED_TABLE_LOCATION_CUSTOM_DDL_TOKEN));
        }
        // The table is partitioned, custom DDL is specified, or there is at least one business object data instance found.
        else
        {
            processBusinessObjectDataForGenerateDdl(customDdlEntity, isPartitioned, tableName, storageEntity, s3BucketName, sb, replacements,
                businessObjectFormat, ifNotExistsOption, businessObjectDataEntities);
        }
    }

    /**
     * Adds the relative "alter table add partition" statements for each of the business object data entity. Please note that each request partition value might
     * result in multiple available business object data entities (subpartitions).
     *
     * @param customDdlEntity the custom DDL entity
     * @param isPartitioned specifies if the table is partitioned or not
     * @param tableName the table name
     * @param storageEntity the storage entity
     * @param s3BucketName the S3 bucket name
     * @param sb the string builder to be updated with the "alter table add partition" statements
     * @param replacements the hash map of string values to be used to substitute the custom DDL tokens with their actual values
     * @param businessObjectFormat the business object format
     * @param ifNotExistsOption specifies if generated DDL contains "if not exists" option
     * @param businessObjectDataEntities the list of business object data entities
     */
    private void processBusinessObjectDataForGenerateDdl(CustomDdlEntity customDdlEntity, Boolean isPartitioned, String tableName, StorageEntity storageEntity,
        String s3BucketName, StringBuilder sb, HashMap<String, String> replacements, BusinessObjectFormat businessObjectFormat, String ifNotExistsOption,
        List<BusinessObjectDataEntity> businessObjectDataEntities)
    {
        if (!CollectionUtils.isEmpty(businessObjectDataEntities))
        {
            // Retrieve all storage unit entities for the storage and the list of business object data entities.
            // Please note that since all business object data is available we expected all business object data to have a storage unit in the storage.
            List<StorageUnitEntity> storageUnitEntities = dmDao.getStorageUnitsByStorageAndBusinessObjectData(storageEntity, businessObjectDataEntities);

            // Load all storage units in a map for easy access.
            Map<Integer, StorageUnitEntity> businessObjectDataIdToStorageUnitMap = getBusinessObjectDataIdToStorageUnitMapping(storageUnitEntities);

            // Retrieve all storage files entities for the storage and the list of business object data entities.
            List<StorageFileEntity> storageFileEntities = dmDao.getStorageFilesByStorageAndBusinessObjectData(storageEntity, businessObjectDataEntities);

            // Load all storage file paths in a map for easy access.
            Map<Integer, List<String>> businessObjectDataIdToStorageFilePathsMap = getBusinessObjectDataIdToStorageFilePathsMapping(storageFileEntities);

            // Process all available business object data instances.
            for (BusinessObjectDataEntity businessObjectDataEntity : businessObjectDataEntities)
            {
                // Get business object data key and S3 key prefix for this business object data.
                BusinessObjectDataKey businessObjectDataKey = dmDaoHelper.getBusinessObjectDataKey(businessObjectDataEntity);
                String s3KeyPrefix = businessObjectDataHelper.buildS3KeyPrefix(businessObjectDataEntity.getBusinessObjectFormat(), businessObjectDataKey);

                // Retrieve storage file paths registered with this business object data in the specified storage.
                List<String> storageFilePaths = businessObjectDataIdToStorageFilePathsMap.containsKey(businessObjectDataEntity.getId()) ?
                    businessObjectDataIdToStorageFilePathsMap.get(businessObjectDataEntity.getId()) : new ArrayList<String>();

                // Validate storage file paths registered with this business object data in the specified storage.
                // The validation check below is required even if we have no storage files registered.
                dmDaoHelper.validateStorageFiles(storageFilePaths, s3KeyPrefix, businessObjectDataEntity, storageEntity.getName());

                // If there are no storage files registered for this storage unit, we should use the storage directory path value.
                if (storageFilePaths.isEmpty())
                {
                    // Since this business object data is selected as being available in the storage, we do not check for null value to be returned here.
                    StorageUnitEntity storageUnitEntity = businessObjectDataIdToStorageUnitMap.get(businessObjectDataEntity.getId());

                    // Validate that directory path value is present and it matches the S3 key prefix.
                    Assert.isTrue(storageUnitEntity.getDirectoryPath() != null && storageUnitEntity.getDirectoryPath().startsWith(s3KeyPrefix), String.format(
                        "Storage directory path \"%s\" registered with business object data {%s} " +
                            "in \"%s\" storage does not match the expected S3 key prefix \"%s\".", storageUnitEntity.getDirectoryPath(),
                        dmDaoHelper.businessObjectDataEntityAltKeyToString(businessObjectDataEntity), storageEntity.getName(), s3KeyPrefix));
                    // Add storage directory path the empty storage files list.
                    // We add a trailing '/' character to the path, since it represents a directory.
                    storageFilePaths.add(storageUnitEntity.getDirectoryPath() + "/");
                }

                if (isPartitioned)
                {
                    // Get partition information. For multiple level partitioning, auto-discover subpartitions (subdirectories)
                    // not already included into the S3 key prefix. Each discovered partition requires a standalone "add partition" clause.
                    // Get a list of subpartitions not already included into the S3 key prefix.
                    int subPartitionValuesCount = dmHelper.getCollectionSize(businessObjectDataKey.getSubPartitionValues());
                    List<SchemaColumn> autoDiscoverableSubPartitionColumns = businessObjectFormat.getSchema().getPartitions()
                        .subList(1 + subPartitionValuesCount, businessObjectFormat.getSchema().getPartitions().size());

                    for (HivePartitionDto hivePartition : getHivePartitions(businessObjectDataKey, autoDiscoverableSubPartitionColumns, s3KeyPrefix,
                        storageFilePaths, businessObjectDataEntity, storageEntity.getName()))
                    {
                        sb.append(String.format("ALTER TABLE `%s` ADD %sPARTITION (", tableName, ifNotExistsOption));
                        // Specify all partition column values.
                        List<String> partitionKeyValuePairs = new ArrayList<>();
                        for (int i = 0; i < businessObjectFormat.getSchema().getPartitions().size(); i++)
                        {
                            String partitionColumnName = businessObjectFormat.getSchema().getPartitions().get(i).getName();
                            String partitionValue = hivePartition.getPartitionValues().get(i);
                            partitionKeyValuePairs.add(String.format("`%s`='%s'", partitionColumnName, partitionValue));
                        }
                        sb.append(StringUtils.join(partitionKeyValuePairs, ", "));
                        sb.append(String.format(") LOCATION 's3n://%s/%s%s';\n", s3BucketName, s3KeyPrefix,
                            StringUtils.isNotBlank(hivePartition.getPath()) ? hivePartition.getPath() : ""));
                    }
                }
                else    // This is a non-partitioned table.
                {
                    // Get location for this non-partitioned table.
                    String tableLocation = String.format("s3n://%s/%s", s3BucketName, s3KeyPrefix);

                    if (customDdlEntity == null)
                    {
                        // Since custom DDL was not specified and this table is not partitioned, add a LOCATION clause.
                        // This is the last line in the non-partitioned table DDL.
                        sb.append(String.format("LOCATION '%s';", tableLocation));
                    }
                    else
                    {
                        // Since custom DDL was used for a non-partitioned table, substitute the relative custom DDL token with the actual table location.
                        replacements.put(NON_PARTITIONED_TABLE_LOCATION_CUSTOM_DDL_TOKEN, tableLocation);
                    }
                }
            }
        }
    }

    /**
     * Returns a map that maps business object data ids to their relative storage units.
     *
     * @param storageUnitEntities the list of storage unit entities
     *
     * @return the map of business object data ids to their relative storage units
     */
    private Map<Integer, StorageUnitEntity> getBusinessObjectDataIdToStorageUnitMapping(List<StorageUnitEntity> storageUnitEntities)
    {
        Map<Integer, StorageUnitEntity> result = new HashMap<>();

        for (StorageUnitEntity storageUnitEntity : storageUnitEntities)
        {
            result.put(storageUnitEntity.getBusinessObjectData().getId(), storageUnitEntity);
        }

        return result;
    }

    /**
     * Returns a map that maps business object data ids to their relative storage file paths.
     *
     * @param storageFileEntities the list of storage file entities
     *
     * @return the map of business object data ids to their relative storage file paths
     */
    private Map<Integer, List<String>> getBusinessObjectDataIdToStorageFilePathsMapping(List<StorageFileEntity> storageFileEntities)
    {
        Map<Integer, List<String>> result = new HashMap<>();

        for (StorageFileEntity storageFileEntity : storageFileEntities)
        {
            Integer businessObjectDataId = storageFileEntity.getStorageUnit().getBusinessObjectData().getId();

            if (result.containsKey(businessObjectDataId))
            {
                result.get(businessObjectDataId).add(storageFileEntity.getPath());
            }
            else
            {
                List<String> businessObjectDataStorageFiles = new ArrayList<>();
                businessObjectDataStorageFiles.add(storageFileEntity.getPath());
                result.put(businessObjectDataId, businessObjectDataStorageFiles);
            }
        }

        return result;
    }

    /**
     * Gets a list of Hive partitions. For single level partitioning, no auto-discovery of sub-partitions (sub-directories) is needed - the business object data
     * will be represented by a single Hive partition instance.  For multiple level partitioning, this method performs an auto-discovery of all sub-partitions
     * (sub-directories) and creates a Hive partition object instance for each partition.
     *
     * @param businessObjectDataKey the business object data key.
     * @param autoDiscoverableSubPartitionColumns the auto-discoverable sub-partition columns.
     * @param s3KeyPrefix the S3 key prefix.
     * @param storageFiles the storage files.
     * @param businessObjectDataEntity the business object data entity.
     * @param storageName the storage name.
     *
     * @return the list of Hive partitions
     */
    public List<HivePartitionDto> getHivePartitions(BusinessObjectDataKey businessObjectDataKey, List<SchemaColumn> autoDiscoverableSubPartitionColumns,
        String s3KeyPrefix, List<String> storageFiles, BusinessObjectDataEntity businessObjectDataEntity, String storageName)
    {
        // We are using linked hash map to preserve the order of the discovered partitions.
        LinkedHashMap<List<String>, HivePartitionDto> linkedHashMap = new LinkedHashMap<>();

        Pattern pattern = getHivePathPattern(autoDiscoverableSubPartitionColumns);
        for (String storageFile : storageFiles)
        {
            // Remove S3 key prefix from the file path. Please note that the storage files are already validated to start with S3 key prefix.
            String relativeFilePath = storageFile.substring(s3KeyPrefix.length());

            // Try to match the relative file path to the expected subpartition folders.
            Matcher matcher = pattern.matcher(relativeFilePath);
            Assert.isTrue(matcher.matches(), String.format("Registered storage file or directory does not match the expected Hive sub-directory pattern. " +
                "Storage: {%s}, file/directory: {%s}, business object data: {%s}, S3 key prefix: {%s}, pattern: {^%s$}", storageName, storageFile,
                dmDaoHelper.businessObjectDataEntityAltKeyToString(businessObjectDataEntity), s3KeyPrefix, pattern.pattern()));

            // Add the top level partition value.
            HivePartitionDto newHivePartition = new HivePartitionDto();
            newHivePartition.getPartitionValues().add(businessObjectDataKey.getPartitionValue());
            newHivePartition.getPartitionValues().addAll(businessObjectDataKey.getSubPartitionValues());
            // Extract relative partition values.
            for (int i = 1; i <= matcher.groupCount(); i++)
            {
                newHivePartition.getPartitionValues().add(matcher.group(i));
            }

            // Remove the trailing "/" plus an optional file name from the file path and store the result string as this partition relative path.
            newHivePartition.setPath(relativeFilePath.replaceAll("/[^/]*$", ""));

            // Check if we already have that partition discovered - that would happen if partition contains multiple data files.
            HivePartitionDto hivePartition = linkedHashMap.get(newHivePartition.getPartitionValues());

            if (hivePartition != null)
            {
                // Partition is already discovered, so just validate that the relative file paths match.
                Assert.isTrue(hivePartition.getPath().equals(newHivePartition.getPath()), String.format(
                    "Found two different locations for the same Hive partition. Storage: {%s}, business object data: {%s}, " +
                        "S3 key prefix: {%s}, path[1]: {%s}, path[2]: {%s}", storageName,
                    dmDaoHelper.businessObjectDataEntityAltKeyToString(businessObjectDataEntity), s3KeyPrefix, hivePartition.getPath(),
                    newHivePartition.getPath()));
            }
            else
            {
                // Add this partition to the hash map of discovered partitions.
                linkedHashMap.put(newHivePartition.getPartitionValues(), newHivePartition);
            }
        }

        List<HivePartitionDto> hivePartitions = new ArrayList<>();
        hivePartitions.addAll(linkedHashMap.values());

        return hivePartitions;
    }

    /**
     * Gets a pattern to match Hive partition sub-directories.
     *
     * @param partitionColumns the list of partition columns
     *
     * @return the newly created pattern to match Hive partition sub-directories.
     */
    public Pattern getHivePathPattern(List<SchemaColumn> partitionColumns)
    {
        StringBuilder sb = new StringBuilder(26);

        // For each partition column, add a regular expression to match "<COLUMN_NAME|COLUMN-NAME>=<VALUE>" sub-directory.
        for (SchemaColumn partitionColumn : partitionColumns)
        {
            String partitionColumnName = partitionColumn.getName();
            // We are using a non-capturing group for the partition column names here - this is done by adding "?:" to the beginning of a capture group.
            sb.append("\\/(?:");
            sb.append(Matcher.quoteReplacement(partitionColumnName));
            // Please note that for subpartition folder, we do support partition column names having all underscores replaced with hyphens.
            sb.append('|');
            sb.append(Matcher.quoteReplacement(partitionColumnName.replace("_", "-")));
            sb.append(")=([^/]+)");
        }

        // Add a regular expression for a trailing "/" and an optional file name.
        sb.append("\\/[^/]*");

        // We do a case-insensitive match for partition column names.
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * Returns the corresponding Hive data type per specified schema column entity.
     *
     * @param schemaColumn the schema column that we want to get the corresponding Hive data type for
     * @param businessObjectFormatEntity the business object format entity that schema column belongs to
     *
     * @return the Hive data type
     * @throws IllegalArgumentException if schema column data type is not supported
     */
    private String getHiveDataType(SchemaColumn schemaColumn, BusinessObjectFormatEntity businessObjectFormatEntity)
    {
        String hiveDataType;

        if (schemaColumn.getType().equalsIgnoreCase("TINYINT") || schemaColumn.getType().equalsIgnoreCase("SMALLINT") ||
            schemaColumn.getType().equalsIgnoreCase("INT") || schemaColumn.getType().equalsIgnoreCase("BIGINT") ||
            schemaColumn.getType().equalsIgnoreCase("FLOAT") || schemaColumn.getType().equalsIgnoreCase("DOUBLE") ||
            schemaColumn.getType().equalsIgnoreCase("TIMESTAMP") || schemaColumn.getType().equalsIgnoreCase("DATE") ||
            schemaColumn.getType().equalsIgnoreCase("STRING") || schemaColumn.getType().equalsIgnoreCase("BOOLEAN") ||
            schemaColumn.getType().equalsIgnoreCase("BINARY"))
        {
            hiveDataType = schemaColumn.getType().toUpperCase();
        }
        else if (schemaColumn.getType().equalsIgnoreCase("DECIMAL") || schemaColumn.getType().equalsIgnoreCase("NUMBER"))
        {
            hiveDataType = StringUtils.isNotBlank(schemaColumn.getSize()) ? String.format("DECIMAL(%s)", schemaColumn.getSize()) : "DECIMAL";
        }
        else if (schemaColumn.getType().equalsIgnoreCase("VARCHAR") || schemaColumn.getType().equalsIgnoreCase("CHAR"))
        {
            hiveDataType = String.format("%s(%s)", schemaColumn.getType().toUpperCase(), schemaColumn.getSize());
        }
        else if (schemaColumn.getType().equalsIgnoreCase("VARCHAR2"))
        {
            hiveDataType = String.format("VARCHAR(%s)", schemaColumn.getSize());
        }
        else
        {
            throw new IllegalArgumentException(String
                .format("Column \"%s\" has an unsupported data type \"%s\" in the schema for business object format {%s}.", schemaColumn.getName(),
                    schemaColumn.getType(), dmDaoHelper.businessObjectFormatEntityAltKeyToString(businessObjectFormatEntity)));
        }

        return hiveDataType;
    }

    /**
     * Returns the corresponding Hive file format.
     *
     * @param businessObjectFormatEntity the business object format entity that schema column belongs to
     *
     * @return the Hive file format
     * @throws IllegalArgumentException if business object format file type is not supported
     */
    private String getHiveFileFormat(BusinessObjectFormatEntity businessObjectFormatEntity)
    {
        String fileFormat = businessObjectFormatEntity.getFileType().getCode();
        String hiveFileFormat;

        if (fileFormat.equalsIgnoreCase(FileTypeEntity.BZ_FILE_TYPE) || fileFormat.equalsIgnoreCase(FileTypeEntity.GZ_FILE_TYPE) ||
            fileFormat.equalsIgnoreCase(FileTypeEntity.TXT_FILE_TYPE))
        {
            hiveFileFormat = TEXT_HIVE_FILE_FORMAT;
        }
        else if (fileFormat.equalsIgnoreCase(FileTypeEntity.PARQUET_FILE_TYPE))
        {
            hiveFileFormat = PARQUET_HIVE_FILE_FORMAT;
        }
        else if (fileFormat.equalsIgnoreCase(FileTypeEntity.ORC_FILE_TYPE))
        {
            hiveFileFormat = ORC_HIVE_FILE_FORMAT;
        }
        else
        {
            throw new IllegalArgumentException(String.format("Unsupported format file type for business object format {%s}.",
                dmDaoHelper.businessObjectFormatEntityAltKeyToString(businessObjectFormatEntity)));
        }

        return hiveFileFormat;
    }

    /**
     * Gets the DDL character value based on the specified configured character value. This method supports UTF-8 encoded strings and will "Hive" escape any
     * non-ASCII printable characters using '\(value)'.
     *
     * @param string the configured character value.
     *
     * @return the DDL character value.
     */
    public String getDdlCharacterValue(String string)
    {
        return getDdlCharacterValue(string, false);
    }

    /**
     * Gets the DDL character value based on the specified configured character value. This method supports UTF-8 encoded strings and will "Hive" escape any
     * non-ASCII printable characters using '\(value)'.
     *
     * @param string the configured character value.
     * @param escapeSingleBackslash specifies if we need to escape a single backslash character with an extra backslash
     *
     * @return the DDL character value.
     */
    public String getDdlCharacterValue(String string, boolean escapeSingleBackslash)
    {
        // Assume the empty string for the return value.
        StringBuilder returnValueStringBuilder = new StringBuilder();

        // If we have an actual character, set the return value based on our rules.
        if (StringUtils.isNotEmpty(string))
        {
            // Convert the string to UTF-8 so we can the proper characters that were sent via XML.
            String utf8String = new String(string.getBytes(Charsets.UTF_8), Charsets.UTF_8);

            // Loop through each character and add each one to the return value.
            for (int i = 0; i < utf8String.length(); i++)
            {
                // Default to the character itself.
                Character character = string.charAt(i);
                String nextValue = character.toString();

                // If the character isn't ASCII printable, then "Hive" escape it.
                if (!CharUtils.isAsciiPrintable(character))
                {
                    // If the character is unprintable, then display it as the ASCII octal value in \000 format.
                    nextValue = String.format("\\%03o", (int) character);
                }

                // Add this character to the return value.
                returnValueStringBuilder.append(nextValue);
            }

            // Check if we need to escape a single backslash character with an extra backslash.
            if (escapeSingleBackslash && returnValueStringBuilder.toString().equals("\\"))
            {
                returnValueStringBuilder.append('\\');
            }
        }

        // Return the value.
        return returnValueStringBuilder.toString();
    }

    /**
     * Escapes single quote characters, if not already escaped, with an extra backslash.
     *
     * @param string the input text
     *
     * @return the output text with all single quote characters escaped by an extra backslash
     */
    public String escapeSingleQuotes(String string)
    {
        Pattern pattern = Pattern.compile("(?<!\\\\)(')");
        Matcher matcher = pattern.matcher(string);
        StringBuffer stringBuffer = new StringBuffer();

        while (matcher.find())
        {
            matcher.appendReplacement(stringBuffer, matcher.group(1).replace("'", "\\\\'"));
        }
        matcher.appendTail(stringBuffer);

        return stringBuffer.toString();
    }
}
