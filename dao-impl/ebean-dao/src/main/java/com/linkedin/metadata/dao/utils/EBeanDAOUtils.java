package com.linkedin.metadata.dao.utils;

import com.linkedin.metadata.aspect.AuditedAspect;
import com.linkedin.metadata.dao.EbeanMetadataAspect;
import com.linkedin.metadata.dao.ListResult;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.metadata.aspect.SoftDeletedAspect;
import io.ebean.SqlRow;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;


/**
 * Miscellaneous utils used by metadata.dao package.
 */
@Slf4j
public class EBeanDAOUtils {

  public static final String DIFFERENT_RESULTS_TEMPLATE = "The results of %s from the new schema table and old schema table are not equal. Reason: %s."
      + "Defaulting to using the value(s) from the old schema table.\nOld schema results: %s\nNew schema results: %s";
  // String stored in metadata_aspect table for soft deleted aspect
  private static final RecordTemplate DELETED_METADATA = new SoftDeletedAspect().setGma_deleted(true);
  public static final String DELETED_VALUE = RecordUtils.toJsonString(DELETED_METADATA);
  private static final long LATEST_VERSION = 0L;

  private EBeanDAOUtils() {
    // Utils class
  }

  /**
   * Given urn string and Urn class, return Urn instance.
   * @param urn urn string
   * @param urnClass urn class
   * @param <URN> Urn instance
   * @return Urn instance
   */
  @Nonnull
  public static <URN> URN getUrn(@Nonnull String urn, @Nonnull Class<URN> urnClass) {
    try {
      final Method getUrn = urnClass.getMethod("createFromString", String.class);
      return urnClass.cast(getUrn.invoke(null, urn));
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new IllegalArgumentException("URN conversion error for " + urn, e);
    }
  }

  /**
   * Calculate the counter part of floorDiv. E.g. ceilDiv(3, 2) = 2.
   * Reference: https://stackoverflow.com/questions/27643616/ceil-conterpart-for-math-floordiv-in-java
   * @param x x integer
   * @param y y integer
   * @return ceiling of x / y
   */
  public static int ceilDiv(int x, int y) {
    return -Math.floorDiv(-x, y);
  }

  /**
   * Compare lists, which should be results from reading the old and new schema tables. If different, log an error and
   * return false. Otherwise, return true.
   * @param resultOld Results from reading the old schema table
   * @param resultNew Results from reading the new schema table
   * @param methodName Name of method that called this function, for logging purposes
   * @return Boolean indicating equivalence
   */
  public static <T> boolean compareResults(@Nullable List<T> resultOld, @Nullable List<T> resultNew, @Nonnull String methodName) {
    if (resultOld == null && resultNew == null) {
      return true;
    }
    if (resultOld == null || resultNew == null) {
      log.warn(String.format(DIFFERENT_RESULTS_TEMPLATE, "One of the results was null.", methodName, resultOld, resultNew));
      return false;
    }
    if (resultOld.size() != resultNew.size()) {
      log.warn(String.format(DIFFERENT_RESULTS_TEMPLATE, "The results are different sizes.", methodName, resultOld, resultNew));
      return false;
    }
    if (resultOld.containsAll(resultNew)) {
      return true;
    }
    log.warn(String.format(DIFFERENT_RESULTS_TEMPLATE, "The elements in the old schema result do not match the elements "
        + "in the new schema result.", methodName, resultOld, resultNew));
    return false;
  }

  /**
   * Compare lists, which should be results from reading the old and new schema tables. If different, log an error and
   * return false. Otherwise, return true.
   * @param resultOld Results from reading the old schema table
   * @param resultNew Results from reading the new schema table
   * @param methodName Name of method that called this function, for logging purposes
   * @return Boolean indicating equivalence
   */
  public static <T> boolean compareResults(@Nullable ListResult<T> resultOld, @Nullable ListResult<T> resultNew,
      @Nonnull String methodName) {
    if (resultOld == null && resultNew == null) {
      return true;
    }
    if (resultOld == null || resultNew == null) {
      log.warn(String.format(DIFFERENT_RESULTS_TEMPLATE, "One of the results was null.", methodName, resultOld, resultNew));
      return false;
    }
    if (resultOld.equals(resultNew)) {
      return true;
    }
    log.warn(String.format(DIFFERENT_RESULTS_TEMPLATE, "Check preceding WARN logs for the reason that the ListResults are not equal",
        methodName, resultOld, resultNew));
    return false;
  }

  /**
   * Checks whether the aspect record has been soft deleted.
   *
   * @param aspect aspect value
   * @param aspectClass the type of the aspect
   * @return boolean representing whether the aspect record has been soft deleted
   */
  public static <ASPECT extends RecordTemplate> boolean isSoftDeletedAspect(@Nonnull EbeanMetadataAspect aspect,
      @Nonnull Class<ASPECT> aspectClass) {
    // Convert metadata string to record template object
    final RecordTemplate metadataRecord = RecordUtils.toRecordTemplate(aspectClass, aspect.getMetadata());
    return metadataRecord.equals(DELETED_METADATA);
  }


  /**
   * Read {@link SqlRow} list into a {@link EbeanMetadataAspect} list.
   * @param sqlRows list of {@link SqlRow}
   * @return list of {@link EbeanMetadataAspect}
   */
  // TODO: make this method private once all users' custom SQL queries have been replaced by DAO-supported methods
  public static List<EbeanMetadataAspect> readSqlRows(List<SqlRow> sqlRows) {
    return sqlRows.stream().flatMap(sqlRow -> {
      List<String> columns = new ArrayList<>();
      sqlRow.keySet().stream().filter(key -> key.startsWith(SQLSchemaUtils.ASPECT_PREFIX) && sqlRow.get(key) != null).forEach(columns::add);

      return columns.stream().map(columnName -> {
        EbeanMetadataAspect ebeanMetadataAspect = new EbeanMetadataAspect();
        String urn = sqlRow.getString("urn");
        AuditedAspect auditedAspect = RecordUtils.toRecordTemplate(AuditedAspect.class, sqlRow.getString(columnName));
        EbeanMetadataAspect.PrimaryKey primaryKey = new EbeanMetadataAspect.PrimaryKey(urn, auditedAspect.getCanonicalName(), LATEST_VERSION);
        ebeanMetadataAspect.setKey(primaryKey);
        ebeanMetadataAspect.setCreatedBy(auditedAspect.getLastmodifiedby());
        ebeanMetadataAspect.setCreatedOn(Timestamp.valueOf(auditedAspect.getLastmodifiedon()));
        ebeanMetadataAspect.setCreatedFor(auditedAspect.getCreatedfor());
        ebeanMetadataAspect.setMetadata(extractAspectJsonString(sqlRow.getString(columnName)));
        return ebeanMetadataAspect;
      });
    }).collect(Collectors.toList());
  }

  /**
   * Extract aspect json string from an AuditedAspect string in its DB format. Return null if aspect json string does not exist.
   * @param auditedAspect an AuditedAspect string in its DB format
   * @return A string which can be deserialized into Aspect object.
   */
  @Nullable
  private static String extractAspectJsonString(@Nonnull final String auditedAspect) {
    try {
      JSONParser jsonParser = new JSONParser();
      JSONObject map = (JSONObject) jsonParser.parse(auditedAspect);
      if (map.containsKey("aspect")) {
        return map.get("aspect").toString();
      }

      return null;
    } catch (ParseException parseException) {
      log.error(String.format("Failed to parse string %s as AuditedAspect. Exception: %s", auditedAspect, parseException));
      throw new RuntimeException(parseException);
    }
  }
}
