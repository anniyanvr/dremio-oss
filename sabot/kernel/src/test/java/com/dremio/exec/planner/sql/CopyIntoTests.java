/*
 * Copyright (C) 2017-2019 Dremio Corporation
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

package com.dremio.exec.planner.sql;

import static com.dremio.exec.planner.sql.DmlQueryTestUtils.PARTITION_COLUMN_ONE_INDEX_SET;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.addQuotes;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.createBasicNonPartitionedAndPartitionedTables;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.createBasicTable;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.createBasicTableWithDates;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.createBasicTableWithDecimals;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.createBasicTableWithDoubles;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.createBasicTableWithFloats;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.createEmptyTableWithListOfType;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.testDmlQuery;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.testMalformedDmlQueries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.util.JsonStringArrayList;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.util.Strings;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import com.dremio.TestBuilder;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.util.FileUtils;
import com.dremio.exec.planner.CopyIntoTablePlanBuilder;
import com.dremio.exec.planner.sql.handlers.query.CopyIntoTableContext;
import com.dremio.exec.planner.sql.parser.SqlCopyIntoTable;
import com.dremio.service.namespace.file.proto.FileType;
import com.dremio.test.UserExceptionAssert;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;


public class CopyIntoTests extends ITDmlQueryBase {

  private static final String DEFAULT_DELIMITER = ",";
  private static final String SOURCE = TEMP_SCHEMA_HADOOP;
  private static String CSV_SOURCE_FOLDER = "/store/text/data/";
  private static String JSON_SOURCE_FOLDER = "/store/json/copyintosource/";

  public static void testEmptyAsNullDefault(BufferAllocator allocator, String source) throws Exception {
    String targetTable = "target1" + "emptyAsNull";
    String ctasQuery = String.format("CREATE TABLE %s.%s (%s)", TEMP_SCHEMA, targetTable, "Index varchar, Height double, Weight double");
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, targetTable);
    test(ctasQuery);
    String relativePath = String.format("/store/text/copyintosource/%s", "emptyvalues.csv");
    validateEmptyAsNull(allocator, source, targetTable, relativePath, "emptyvalues.csv", "csv");
    test(dropQuery);
    test(ctasQuery);
    relativePath = JSON_SOURCE_FOLDER + "/" + "jsonWithEmptyValues.json";
    validateEmptyAsNull(allocator, source, targetTable, relativePath, "source1.json", "json");
    test(dropQuery);
  }

  public static void testFilesLimit(BufferAllocator allocator, String source) throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTableWithDates(source, 2, 5, 0, "2000-01-01", "YYYY-MM-DD");
         DmlQueryTestUtils.Table sourceTable1 = createBasicTableWithDates(source, 2, 2, 5, "2000-01-01", "YYYY-MM-DD");
         DmlQueryTestUtils.Table sourceTable2 = createBasicTableWithDates(source, 2, 4, 7, "2000-01-01", "YYYY-MM-DD")) {
      StringBuilder fileNames = new StringBuilder();
      for(int i = 0 ; i < CopyIntoTableContext.MAX_FILES_ALLOWED + 1;  i += 1) {
        fileNames.append("'" + i + ".csv',");
      }
      boolean exceptionThrown = false;
      try {
        testCopyCSVFiles(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{sourceTable2},
          "COPY INTO %s FROM '%s' FILES( " + fileNames + " '%s') (DATE_FORMAT 'YYYY-MM-DD\"T\"HH24:MI:SS.FFF', RECORD_DELIMITER '\n', FIELD_DELIMITER ',', ON_ERROR 'ABORT_COPY')", true);
      } catch(Exception e) {
        exceptionThrown = true;
        Assert.assertTrue(e.getMessage(), e.getMessage().contains(String.format("Maximum number of files allowed in the FILES clause is %s", CopyIntoTableContext.MAX_FILES_ALLOWED)));
      }
      Assert.assertTrue( "Expected exception for file limit not thrown", exceptionThrown);
    }
  }

  private static void validateEmptyAsNull(BufferAllocator allocator, String source, String targetTable, String relativePath, String fileName, String fileFormat) throws Exception {
    File location = createTempLocation();
    File newSourceFile = new File(location.toString(), fileName);
    File oldSourceFile = FileUtils.getResourceAsFile(relativePath);
    Files.copy(oldSourceFile, newSourceFile);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT '%s' (RECORD_DELIMITER '\n', TRIM_SPACE 'true', EMPTY_AS_NULL 'false')", TEMP_SCHEMA, targetTable, storageLocation, fileFormat);
    boolean exceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      exceptionThrown = true;
      if(fileFormat.equals("csv")) {
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("Error processing input: "));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("line=2"));
      } else {
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("Error parsing JSON"));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("Line: 8, Record: 2"));
      }
      Assert.assertTrue(e.getMessage(), e.getMessage().contains(fileName));
    }

    Assert.assertTrue("Expected exception not thrown", exceptionThrown);
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT '%s' (RECORD_DELIMITER '\n', TRIM_SPACE 'true')", TEMP_SCHEMA, targetTable, storageLocation, fileFormat);
    test(copyIntoQuery);
    new TestBuilder(allocator)
      .sqlQuery("SELECT index FROM %s.%s where index = '3'", TEMP_SCHEMA, targetTable)
      .unOrdered()
      .baselineColumns("index")
      .baselineValues("3")
      .go();
    Assert.assertTrue(newSourceFile.delete());
  }

  public static void testCSVSingleBoolCol(BufferAllocator allocator, String source) throws Exception {
    File location = createTempLocation();
    String targetTable = "singleBoolColTable";
    File newSourceFile = createTableAndGenerateSourceFiles(targetTable, "colbool BOOLEAN", "singleBoolCol.csv", location, true);
    String storageLocation = "'@" + source + "/" + location.getName() + "'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex 'singleBoolCol\\.csv' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, targetTable, storageLocation);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, targetTable)
      .unOrdered()
      .baselineColumns("colbool")
      .baselineValues(true)
      .baselineValues(false)
      .baselineValues(true)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, targetTable);
    test(dropQuery);
  }

  public static void testCSVWithEscapeChar(BufferAllocator allocator, String source) throws Exception {
    File location = createTempLocation();
    String storageLocation = "'@" + source + "/" + location.getName() + "'";
    String targetTable = "escapeCharTestTable";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex 'escapeCharTest\\.csv' (RECORD_DELIMITER '\n', ESCAPE_CHAR '\\\\')", TEMP_SCHEMA, targetTable, storageLocation);
    File newSourceFile = createTableAndGenerateSourceFiles(targetTable, "col1 VARCHAR, col2 VARCHAR, col3 VARCHAR", "escapeCharTest.csv", location, true);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, targetTable)
      .unOrdered()
      .baselineColumns("col1", "col2", "col3")
      .baselineValues("1\"2", "3`\"4", "5\"\"6")
      .baselineValues("7\"\"\"\"2", "3\"4", "5|\"6")
      .go();
  }
  @Test
  public void testMalformedQueries() throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTable(SOURCE,2, 0)) {
      testMalformedDmlQueries(new Object[]{targetTable.fqn, SOURCE},
        "COPY INTO",
        "COPY INTO %s",
        "COPY INTO %s FROM",
        "COPY INTO %s FROM %s",
        "COPY INTO %s FROM %s FILES",
        "COPY INTO %s FROM %s FILES(",
        "COPY INTO %s FROM %s FILES()",
        "COPY INTO %s FROM %s FILES()",
        "COPY INTO %s FROM %s FILES(1)",
        "COPY INTO %s FROM %s FILES('1',)",
        "COPY INTO %s FROM %s REGEX",
        "COPY INTO %s FROM %s REGEX HELLO",
        "COPY INTO %s FROM %s REGEX 'HELLO",
        "COPY INTO %s FROM %s REGEX '.*.csv' FILE_FORMA" ,
        "COPY INTO %s FROM %s REGEX '.*.csv' FILE_FORMA ''" ,
        "COPY INTO %s FROM %s REGEX '.*.csv' (" ,
        "COPY INTO %s FROM %s REGEX '.*.csv' ()" ,
        "COPY INTO %s FROM %s REGEX '.*.csv' (option)"
      );
    }
  }

  @Test
  public void testUnparseSqlCopyIntoTableNode() throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTable(0, SOURCE , 2, 2)) {

      final String regexQuery = String.format("copy INTO %s FROM 'somewhere' REGEX '.*.csv' (" +
        "NULL_IF ('hello', 'world')," +
        "DATE_FORMAT 'yyyy-MM-dd'," +
        "TIME_FORMAT 'HH-mm-ss-ns'," +
        "TIMESTAMP_FORMAT 'yyyy-mm-dd hh:mm:ss. fffffffff'," +
        "TRIM_SPACE 'true'," +
        "QUOTE_CHAR '\"'," +
        "ESCAPE_CHAR 'e'," +
        "EMPTY_AS_NULL 'true'," +
        "RECORD_DELIMITER '\n', " +
        "FIELD_DELIMITER ',', " +
        "ON_ERROR 'ABORT_COPY'" +
        ")", targetTable.fqn);

      String expected = String.format("copy INTO %s FROM 'somewhere' REGEX '.*.csv' (" +
        "'NULL_IF' ('hello', 'world'), " +
        "'DATE_FORMAT' 'yyyy-MM-dd', " +
        "'TIME_FORMAT' 'HH-mm-ss-ns', " +
        "'TIMESTAMP_FORMAT' 'yyyy-mm-dd hh:mm:ss. fffffffff', " +
        "'TRIM_SPACE' 'true', " +
        "'QUOTE_CHAR' '\"', " +
        "'ESCAPE_CHAR' 'e', " +
        "'EMPTY_AS_NULL' 'true', " +
        "'RECORD_DELIMITER' '\n', " +
        "'FIELD_DELIMITER' ',', " +
        "'ON_ERROR' 'ABORT_COPY'" +
          ")", "\"" + SOURCE + "\"." + addQuotes(targetTable.name));
      parseAndValidateSqlNode(regexQuery, expected);

      final String filesQuery = String.format("copy INTO %s FROM 'somewhere' Files('1', '2') (RECORD_DELIMITER '\n', FIELD_DELIMITER ',', ON_ERROR 'ABORT_COPY')", targetTable.fqn);
      expected = String.format("copy INTO %s FROM 'somewhere' files ('1', '2') ('RECORD_DELIMITER' '\n', 'FIELD_DELIMITER' ',', 'ON_ERROR' 'ABORT_COPY')",
        "\"" + SOURCE + "\"." + addQuotes(targetTable.name));
      parseAndValidateSqlNode(filesQuery, expected);
    }
  }

  private static void testInvalidCopyIntoInputsInternal(String storageLocation,
                                               List<String> files,
                                               Optional<String> filePattern,
                                               Optional<String> fileFormat,
                                               List<String> optionsList,
                                               List<Object> optionValueList,
                                               String expectedErrorMsg) {
    final SqlCopyIntoTable call = Mockito.mock(SqlCopyIntoTable.class);
    when(call.getStorageLocation()).thenReturn(storageLocation);
    when(call.getFiles()).thenReturn(files);
    when(call.getFilePattern()).thenReturn(filePattern);
    when(call.getFileFormat()).thenReturn(fileFormat);
    when(call.getOptionsList()).thenReturn(optionsList);
    when(call.getOptionsValueList()).thenReturn(optionValueList);

    UserExceptionAssert.assertThatThrownBy(() -> new CopyIntoTableContext(call))
       .satisfiesAnyOf(
        ex -> assertThat(ex).hasMessageContaining(expectedErrorMsg));
  }

  @Test
  public void testInvalidCopyIntoInputsInternal() throws Exception {
    // storage location does not start with '@'
    testInvalidCopyIntoInputsInternal(
      "test/folder1/file1.csv",
      Collections.emptyList(),
      Optional.empty(),
      Optional.empty(),
      ImmutableList.of("RECORD_DELIMITER", "ON_ERROR"),
      ImmutableList.of("\n", "ABORT_COPY"),
      "Specified location clause not found, Dremio sources should precede with '@'");

    // file is specified by both storage location and FILES
    testInvalidCopyIntoInputsInternal(
      "@test/folder1/file1.csv",
      ImmutableList.of("file1.csv", "file2.csv"),
      Optional.empty(),
      Optional.empty(),
      ImmutableList.of("RECORD_DELIMITER", "ON_ERROR"),
      ImmutableList.of("\n", "ABORT_COPY"),
      "When specifying 'FILES' or 'REGEX' location_clause must end with a directory, found a file");

    // file is specified by both storage location and REGEX
    testInvalidCopyIntoInputsInternal(
      "@test/folder1/file1.csv",
      Collections.emptyList(),
      Optional.of("*.csv"),
      Optional.empty(),
      ImmutableList.of("RECORD_DELIMITER", "ON_ERROR"),
      ImmutableList.of("\n", "ABORT_COPY"),
      "When specifying 'FILES' or 'REGEX' location_clause must end with a directory, found a file");

    // storage location is not a file.  FILES, REGEX and FILE_FORMAT are all empty
    testInvalidCopyIntoInputsInternal(
      "@test/folder1",
      Collections.emptyList(),
      Optional.empty(),
      Optional.empty(),
      ImmutableList.of("RECORD_DELIMITER", "ON_ERROR"),
      ImmutableList.of("\n", "ABORT_COPY"),
      "File format could not be inferred from the file extension, please specify FILE_FORMAT option.");

    // Mixed file extensions
    testInvalidCopyIntoInputsInternal(
      "@test/folder1",
      ImmutableList.of("file1.csv", "file2.json"),
      Optional.empty(),
      Optional.empty(),
      ImmutableList.of("RECORD_DELIMITER", "ON_ERROR"),
      ImmutableList.of("\n", "ABORT_COPY"),
      "Files with only one type");

    // Mixed file extensions
    testInvalidCopyIntoInputsInternal(
      "@test/folder1",
      ImmutableList.of("file1.hello", "file2.hello"),
      Optional.empty(),
      Optional.empty(),
      ImmutableList.of("RECORD_DELIMITER", "ON_ERROR"),
      ImmutableList.of("\n", "ABORT_COPY"),
      "Specified File Format \'HELLO\'");
  }

  private void testCopyIntoTableContextInternal(String storageLocation,
                                        List<String> files,
                                        Optional<String> filePattern,
                                        Optional<String> fileFormat,
                                        List<String> optionsList,
                                        List<Object> optionValueList,
                                        String expectedNormalizedStorageLocation,
                                        List<String> expectedfiles,
                                        FileType expectedfileFormat,
                                        Map<CopyIntoTableContext.FormatOption, Object> expectedFormatOptions,
                                        Map<CopyIntoTableContext.CopyOption, Object> expectedcCopyOptions) throws Exception {
    final SqlCopyIntoTable call = Mockito.mock(SqlCopyIntoTable.class);
    when(call.getStorageLocation()).thenReturn(storageLocation);
    when(call.getFiles()).thenReturn(files);
    when(call.getFilePattern()).thenReturn(filePattern);
    when(call.getFileFormat()).thenReturn(fileFormat);
    when(call.getOptionsList()).thenReturn(optionsList);
    when(call.getOptionsValueList()).thenReturn(optionValueList);

    CopyIntoTableContext context = new CopyIntoTableContext(call);

    assertEquals(expectedNormalizedStorageLocation, context.getStorageLocation());
    assertEquals(expectedfiles, context.getFiles());
    assertEquals(expectedfileFormat, context.getFileFormat());
    assertEquals(expectedFormatOptions.toString(), context.getFormatOptions().toString());
    assertEquals(expectedcCopyOptions, context.getCopyOptions());
  }

@Test
  public void testCopyIntoTableContext() throws Exception {
    // file is specified in storage location
  testCopyIntoTableContextInternal(
    "@test/folder1/file1.csv",
    Collections.emptyList(),
    Optional.empty(),
    Optional.empty(),
    ImmutableList.of("RECORD_DELIMITER", "ON_ERROR"),
    ImmutableList.of("\n", "ABORT_COPY"),
    "test.folder1",
    ImmutableList.of("file1.csv"),
    FileType.TEXT,
    ImmutableMap.of(CopyIntoTableContext.FormatOption.RECORD_DELIMITER, '\n'),
    ImmutableMap.of(CopyIntoTableContext.CopyOption.ON_ERROR, CopyIntoTableContext.OnErrorAction.ABORT_COPY));

  // storage location is not a file. Files are specified in FILES
  testCopyIntoTableContextInternal(
    "@test/folder1",
    ImmutableList.of("file1.csv", "file2.csv"),
    Optional.empty(),
    Optional.empty(),
    ImmutableList.of("RECORD_DELIMITER", "ON_ERROR"),
    ImmutableList.of("\n", "ABORT_COPY"),
    "test.folder1",
    ImmutableList.of("file1.csv", "file2.csv"),
    FileType.TEXT,
    ImmutableMap.of(CopyIntoTableContext.FormatOption.RECORD_DELIMITER, '\n'),
    ImmutableMap.of(CopyIntoTableContext.CopyOption.ON_ERROR, CopyIntoTableContext.OnErrorAction.ABORT_COPY));

  // storage location is not a file. Files are specified in FILES
  testCopyIntoTableContextInternal(
    "@test/folder1",
    Collections.emptyList(),
    Optional.of("*.csv"),
    Optional.empty(),
    ImmutableList.of("RECORD_DELIMITER", "ON_ERROR"),
    ImmutableList.of("\n", "ABORT_COPY"),
    "test.folder1",
    Collections.emptyList(),
    FileType.TEXT,
    ImmutableMap.of(CopyIntoTableContext.FormatOption.RECORD_DELIMITER, '\n'),
    ImmutableMap.of(CopyIntoTableContext.CopyOption.ON_ERROR, CopyIntoTableContext.OnErrorAction.ABORT_COPY));

  // User specified format is different file extension. user specified format has higher priority
  testCopyIntoTableContextInternal(
    "@test/folder1",
    ImmutableList.of("file1.csv", "file2.csv"),
    Optional.empty(),
    Optional.of("json"),
    ImmutableList.of("RECORD_DELIMITER", "ON_ERROR"),
    ImmutableList.of("\n", "ABORT_COPY"),
    "test.folder1",
    ImmutableList.of("file1.csv", "file2.csv"),
    FileType.JSON,
    ImmutableMap.of(CopyIntoTableContext.FormatOption.RECORD_DELIMITER, '\n'),
    ImmutableMap.of(CopyIntoTableContext.CopyOption.ON_ERROR, CopyIntoTableContext.OnErrorAction.ABORT_COPY));
  }

  private static String convertToCSVRow(Object[] row, String delimiter) {
    return Stream.of(row)
      .map(x -> x.toString())
      .collect(Collectors.joining(delimiter));
  }

  private static void writeAsCSV(Object[][] data, String location, String csvFileName, String delimiter) throws IOException {
    File csvOutputFile = new File(location, csvFileName);
    csvOutputFile.createNewFile();
    try (PrintWriter pw = new PrintWriter(csvOutputFile)) {
      // print header
      List<String> columns = new ArrayList<>();
      for (int c = 0; c < data[0].length; c++) {
        if (c == 0) {
          columns.add("id");
        } else {
          columns.add(String.format("column_%s", c - 1));
        }
      }
      pw.println(convertToCSVRow(columns.toArray(), delimiter));

      // print data
      Stream.of(data)
        .map(row -> convertToCSVRow(row, delimiter))
        .forEach(pw::println);
    }

    assertTrue(csvOutputFile.exists());
  }

  private static String convertToJSONStruct(Object[] row, String[] columns) {
    StringBuilder builder = new StringBuilder();
    builder.append("{\n");
    int columnCount = columns.length;

    for(int i = 0; i < columnCount; i++) {
      if(row[i] instanceof ArrayList) {
        // We want each entry inside the list to be enclosed within doubles quotes.
        List<String> listData = ((ArrayList<?>) row[i]).stream().map(entry -> "\"" + entry.toString() + "\"").collect(Collectors.toList());
        String listDataString = listData.toString();
        builder.append(columns[i] + ":" + listDataString + "\n");
        continue;
      } else {
        builder.append(columns[i] + ":\"" + row[i].toString() + "\"");
      }
      if(i != columnCount - 1) {
        builder.append(",");
      }
      builder.append("\n");
    }

    builder.append("}\n");

    return builder.toString();
  }

  private static void writeAsJSON(DmlQueryTestUtils.Table table, String location, String jsonFileName) throws Exception {
    File jsonOutputFile = new File(location, jsonFileName);
    jsonOutputFile.createNewFile();

    String[] columns = table.columns;
    Object[][] data = table.originalData;

    try(PrintWriter pw = new PrintWriter(jsonOutputFile)) {
      Stream.of(data)
        .map(row -> convertToJSONStruct(row, columns))
        .forEach(pw::println);
    }
    assertTrue(jsonOutputFile.exists());
  }

  public static File createTempLocation() {
    String locationName = RandomStringUtils.randomAlphanumeric(8);
    File location = new File(getDfsTestTmpSchemaLocation(), locationName);
    location.mkdirs();
    return location;
  }

  private static void testCSVWithFilter(BufferAllocator allocator, String source, DmlQueryTestUtils.Table targetTable, DmlQueryTestUtils.Table[] sourceTables, DmlQueryTestUtils.Table[] validDataTables, String query) throws Exception {
    File location = createTempLocation();

    Object[][] expectedDataAfterCopyInto = targetTable.originalData;
    int expectedCopiedRowCount = 0;
    for (DmlQueryTestUtils.Table table : sourceTables) {
      writeAsCSV(table.originalData, location.toString(), table.name + ".csv", DEFAULT_DELIMITER);
    }

    for (DmlQueryTestUtils.Table table : validDataTables) {
      expectedDataAfterCopyInto = ArrayUtils.addAll(expectedDataAfterCopyInto, table.originalData);
      expectedCopiedRowCount += table.originalData.length;
    }

    testDmlQuery(allocator,
            query,
            new Object[]{targetTable.fqn,
              "@" + source + "/" + location.getName()},
            targetTable,
            expectedCopiedRowCount,
            expectedDataAfterCopyInto);
  }

  private static void testJSON(BufferAllocator allocator, String source, DmlQueryTestUtils.Table targetTable, DmlQueryTestUtils.Table[] sourceTables, DmlQueryTestUtils.Table[] validDataTables, String query) throws Exception {
    File location = createTempLocation();

    for(DmlQueryTestUtils.Table table : sourceTables) {
      writeAsJSON(table, location.toString(), table.name + ".json");
    }

    Object[][] expectedDataAfterCopyInto = targetTable.originalData;
    int expectedCopiedRowCount = 0;

    for (DmlQueryTestUtils.Table table : validDataTables) {
      expectedDataAfterCopyInto = ArrayUtils.addAll(expectedDataAfterCopyInto, table.originalData);
      expectedCopiedRowCount += table.originalData.length;
    }

    testDmlQuery(allocator,
      query,
      new Object[]{targetTable.fqn,
        "@" + source + "/" + location.getName()},
      targetTable,
      expectedCopiedRowCount,
      expectedDataAfterCopyInto);
  }

  private static void testCopyCSVFiles(BufferAllocator allocator, String source, DmlQueryTestUtils.Table targetTable, DmlQueryTestUtils.Table[] sourceTables, DmlQueryTestUtils.Table[] validDataTables, String query, boolean shouldUseRootLocation) throws Exception {
    File location = shouldUseRootLocation?  new File(getDfsTestTmpSchemaLocation()) : createTempLocation();
    Object[][] expectedDataAfterCopyInto = targetTable.originalData;
    int expectedCopiedRowCount = 0;
    for (DmlQueryTestUtils.Table table : sourceTables) {
      writeAsCSV(table.originalData, location.toString(), table.name + ".csv", DEFAULT_DELIMITER);
    }

    File locationDir  = new File(location.toString());
    if(!shouldUseRootLocation) {
      Assert.assertEquals(sourceTables.length, locationDir.listFiles().length);
    }
    Object [] args =  new Object[validDataTables.length + 2];
    args[0] = targetTable.fqn;
    String locationName  = shouldUseRootLocation? Strings.EMPTY : location.getName();
    args[1] = "@" + source + "/" +  locationName;
    int argPointer  = 2;
    for (DmlQueryTestUtils.Table table : validDataTables) {
      expectedDataAfterCopyInto = ArrayUtils.addAll(expectedDataAfterCopyInto, table.originalData);
      expectedCopiedRowCount += table.originalData.length;
      args[argPointer++] =  table.name + ".csv";
    }

    testDmlQuery(allocator,
      query,
      args,
      targetTable,
      expectedCopiedRowCount,
      expectedDataAfterCopyInto);
  }

  public static void testCSVWithFilePatternStringType(BufferAllocator allocator, String source) throws Exception {
    try (DmlQueryTestUtils.Tables targetTables = createBasicNonPartitionedAndPartitionedTables(source, 2, 5, 0, PARTITION_COLUMN_ONE_INDEX_SET);
         DmlQueryTestUtils.Tables sourceTable1s = createBasicNonPartitionedAndPartitionedTables(source, 2, 2, 5, PARTITION_COLUMN_ONE_INDEX_SET);
         DmlQueryTestUtils.Tables sourceTable2s = createBasicNonPartitionedAndPartitionedTables(source, 2, 4, 7, PARTITION_COLUMN_ONE_INDEX_SET);) {
      for (int i = 0; i < targetTables.tables.length; i++) {
        DmlQueryTestUtils.Table targetTable = targetTables.tables[i];
        DmlQueryTestUtils.Table sourceTable1 = sourceTable1s.tables[i];
        DmlQueryTestUtils.Table sourceTable2 = sourceTable2s.tables[i];
        testCSVWithFilter(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2},
          "copy INTO %s FROM '%s' REGEX '.*.csv' (RECORD_DELIMITER '\n', FIELD_DELIMITER ',', ON_ERROR 'ABORT_COPY')");
      }
    }
  }

  public static void validateUserException(BufferAllocator allocator, String source, String tableDef, String sourceFile, String columnSelect, Integer expectValue, String relativePath, String expectedExceptionMessage)  throws Exception {
    boolean exceptionThrown = false;
    try {
      testSource(allocator, source, tableDef, sourceFile,columnSelect , expectValue, relativePath);
    } catch(UserException e) {
      exceptionThrown = true;
      assertTrue("Exception not thrown for duplicate column,  message " + e.getMessage(), e.getMessage().contains(expectedExceptionMessage));
    }
    Assert.assertTrue("Expected exception not thrown", exceptionThrown);
  }

  public static void testCSVWithDuplicateColumnNames(BufferAllocator allocator, String source) throws Exception {
    testSource(allocator, source, "\"year\" varchar, make varchar", "cars-badheaders.csvh", "make", "Ford", CSV_SOURCE_FOLDER);
    validateUserException(allocator, source, "\"year\" varchar, make varchar, Price varchar", "cars-badheaders.csvh", "\"year\"", null, CSV_SOURCE_FOLDER, "DATA_READ ERROR: Duplicate column name Price.");
  }

  public static void testJsonWithDuplicateColumnNames(BufferAllocator allocator, String source) throws Exception {
    validateUserException(allocator, source, "col1 array<struct<c1 int, c2 float, c3 varchar>>, col3 varchar", "dup_columnsAtRoot.json", "col1[1]['c3']", null, JSON_SOURCE_FOLDER, "Duplicate column name col3.");
    validateUserException(allocator, source, "col1 array<struct<c1 int, c2 float, c3 varchar>>, col3 varchar", "dup_columnsAtNested.json", "col1[1]['c3']", null, JSON_SOURCE_FOLDER, "Duplicate column name c3.");
  }

  public static void testJsonWithNullValuesAtRootLevel(BufferAllocator allocator, String source) throws Exception {
    String tableName = "target_s6";
    String ctasQuery = String.format("CREATE TABLE %s.%s (col1 struct< col2: array<int>, col3: varchar>)", TEMP_SCHEMA, tableName);
    test(ctasQuery);
    String relativePath =  JSON_SOURCE_FOLDER + "source6.json";
    File location = createTempLocation();
    File newSourceFile = new File(location.toString(), "source6.json");
    File oldSourceFile = FileUtils.getResourceAsFile(relativePath);
    Files.copy(oldSourceFile, newSourceFile);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT 'json'", TEMP_SCHEMA, tableName, storageLocation);
    test(copyIntoQuery);
    new TestBuilder(allocator)
      .sqlQuery("SELECT %s as column FROM %s.%s", "col1['col3']" , TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("column")
      .baselineValues(null).baselineValues("data1").baselineValues(null).baselineValues("data2").baselineValues(null)
      .ordered()
      .go();
    Assert.assertTrue(newSourceFile.delete());
  }

  public static void testCSVWithRegex(BufferAllocator allocator, String source) throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTable(0, source, 2, 5);
         DmlQueryTestUtils.Table sourceTable1 = createBasicTable(5, source, 2, 2, "f1");
         DmlQueryTestUtils.Table sourceTable2 = createBasicTable(7, source, 2, 4, "f2")) {
        testCSVWithFilter(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{sourceTable1},
                "copy INTO %s FROM '%s' REGEX '.*1.csv' (RECORD_DELIMITER '\n', FIELD_DELIMITER ',', ON_ERROR 'ABORT_COPY')");
    }
  }

  public static void testCSVWithQuote(BufferAllocator allocator, String source) throws Exception {
    String tableName = "target1" + "csvWithQuote";
    String ctasQuery = String.format("CREATE TABLE %s.%s (%s)", TEMP_SCHEMA, tableName, "col1 varchar, col2 varchar, col3 varchar");
    test(ctasQuery);
    String relativePath = String.format("/store/text/copyintosource/%s", "quotecharvalues.csv");
    File location = createTempLocation();
    File newSourceFile = new File(location.toString(), "src1.csv");
    File oldSourceFile = FileUtils.getResourceAsFile(relativePath);
    Files.copy(oldSourceFile, newSourceFile);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'csv\' (RECORD_DELIMITER '\n' , QUOTE_CHAR '|')", TEMP_SCHEMA, tableName, storageLocation);
    test(copyIntoQuery);
    new TestBuilder(allocator)
            .sqlQuery("SELECT col2 FROM %s.%s", TEMP_SCHEMA, tableName)
            .unOrdered()
            .baselineColumns("col2")
            .baselineValues("b")
            .baselineValues("e,f")
            .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testCSVJSONWithSpace(BufferAllocator allocator, String source) throws Exception {
    testCSVWithSpace(allocator, source);
    testJSONWithSpace(allocator, source);
  }

  private static void testCSVWithSpace(BufferAllocator allocator, String source) throws Exception {
    String ctasQuery = String.format("CREATE TABLE %s.%s (%s)", TEMP_SCHEMA, "target1", "col1 varchar, col2 varchar, col3 varchar");
    test(ctasQuery);
    String relativePath = String.format("/store/text/copyintosource/%s", "csvwithspace.csv");
    File location = createTempLocation();
    File newSourceFile = new File(location.toString(), "src1.csv");
    File oldSourceFile = FileUtils.getResourceAsFile(relativePath);
    Files.copy(oldSourceFile, newSourceFile);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'csv\' (RECORD_DELIMITER '\n' , TRIM_SPACE 'true')", TEMP_SCHEMA, "target1", storageLocation);
    test(copyIntoQuery);
    new TestBuilder(allocator)
            .sqlQuery("SELECT col2 FROM %s.%s", TEMP_SCHEMA, "target1")
            .unOrdered()
            .baselineColumns("col2")
            .baselineValues("20,30")
            .baselineValues("  twospace  ")
            .baselineValues("onespace")
            .baselineValues("this one has significant spaces in between but no quotes")
            .baselineValues("this one has significant spaces in between quotes")
            .go();

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'csv\' (RECORD_DELIMITER '\n' , TRIM_SPACE 'false')", TEMP_SCHEMA, "target1", storageLocation);
    test(copyIntoQuery);
    new TestBuilder(allocator)
            .sqlQuery("SELECT col2 FROM %s.%s", TEMP_SCHEMA, "target1")
            .unOrdered()
            .baselineColumns("col2")
            .baselineValues("20,30")
            .baselineValues("  twospace  ")
            .baselineValues("onespace")
            .baselineValues("this one has significant spaces in between but no quotes")
            .baselineValues("this one has significant spaces in between quotes")
            .baselineValues("  \"20")
            .baselineValues("  \"  twospace  \"  ")
            .baselineValues(" onespace ")
            .baselineValues("   this one has significant spaces in between but no quotes   ")
            .baselineValues("     \"this one has significant spaces in between quotes\"     ")
            .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, "target1");
    test(dropQuery);
  }

  private static void testJSONWithSpace(BufferAllocator allocator, String source) throws Exception {
    String ctasQuery = String.format("CREATE TABLE %s.%s (%s)", TEMP_SCHEMA, "target1", "col1 varchar, col2 varchar, col3 varchar");
    test(ctasQuery);
    String relativePath = String.format("/store/json/copyintosource/%s", "jsonwithspace.json");
    File location = createTempLocation();
    File newSourceFile = new File(location.toString(), "src1.json");
    File oldSourceFile = FileUtils.getResourceAsFile(relativePath);
    Files.copy(oldSourceFile, newSourceFile);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'json\' (TRIM_SPACE 'true')", TEMP_SCHEMA, "target1", storageLocation);
    test(copyIntoQuery);
    new TestBuilder(allocator)
            .sqlQuery("SELECT col1,col2,col3 FROM %s.%s", TEMP_SCHEMA, "target1")
            .unOrdered()
            .baselineColumns("col1", "col2", "col3")
            .baselineValues("10", "twospace", "' one space '")
            .go();

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'json\' (TRIM_SPACE 'false')", TEMP_SCHEMA, "target1", storageLocation);
    test(copyIntoQuery);
    new TestBuilder(allocator)
            .sqlQuery("SELECT col1,col2,col3 FROM %s.%s", TEMP_SCHEMA, "target1")
            .unOrdered()
            .baselineColumns("col1", "col2", "col3")
            .baselineValues("10", "twospace", "' one space '")
            .baselineValues("10", "  twospace  ", " ' one space ' ")
            .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, "target1");
    test(dropQuery);
  }
  public static void testCSVWithEmpyData(BufferAllocator allocator, String source) throws Exception {
    String ctasQuery = String.format("CREATE TABLE %s.%s (%s)", TEMP_SCHEMA, "target1", "Index varchar, Height double, Weight double");
    test(ctasQuery);
    String relativePath = String.format("/store/text/copyintosource/%s", "emptyvalues.csv");
    File location = createTempLocation();
    File newSourceFile = new File(location.toString(), "src1.csv");
    File oldSourceFile = FileUtils.getResourceAsFile(relativePath);
    Files.copy(oldSourceFile, newSourceFile);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'csv\' (RECORD_DELIMITER '\n', EMPTY_AS_NULL 'true', TRIM_SPACE 'true')", TEMP_SCHEMA, "target1", storageLocation);
    test(copyIntoQuery);
    new TestBuilder(allocator)
            .sqlQuery("SELECT index FROM %s.%s where index = '3'", TEMP_SCHEMA, "target1")
            .unOrdered()
            .baselineColumns("index")
            .baselineValues("3")
            .go();

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'csv\' (RECORD_DELIMITER '\n', EMPTY_AS_NULL 'true')", TEMP_SCHEMA, "target1", storageLocation);
    test(copyIntoQuery);
    new TestBuilder(allocator)
            .sqlQuery("SELECT index FROM %s.%s where index = '3  '", TEMP_SCHEMA, "target1")
            .unOrdered()
            .baselineColumns("index")
            .baselineValues("3  ")
            .go();

    new TestBuilder(allocator)
            .sqlQuery("SELECT count(*) as col1 FROM %s.%s where height is null", TEMP_SCHEMA, "target1")
            .unOrdered()
            .baselineColumns("col1")
            .baselineValues(2L)
            .go();

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'csv\' (RECORD_DELIMITER '\n', EMPTY_AS_NULL 'false')", TEMP_SCHEMA, "target1", storageLocation);
    boolean exceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      exceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("line=2"));
    }
    Assert.assertTrue("Expected exception not thrown", exceptionThrown);
    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, "target1");
    test(dropQuery);
  }

  public static  void testCSVJSONWithNoTargetColumn(BufferAllocator allocator, String source) throws Exception {
    String ctasQuery = String.format("CREATE TABLE %s.%s (%s)", TEMP_SCHEMA, "target1", "col1 varchar, col2 int");
    test(ctasQuery);
    String relativePath = String.format("/store/text/copyintosource/%s", "no_column_match.csv");
    File location = createTempLocation();
    File newSourceFile = new File(location.toString(), "src1.csv");
    File oldSourceFile = FileUtils.getResourceAsFile(relativePath);
    Files.copy(oldSourceFile, newSourceFile);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'csv\' (RECORD_DELIMITER '\n', EMPTY_AS_NULL 'true', TRIM_SPACE 'true')", TEMP_SCHEMA, "target1", storageLocation);
    boolean exceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      exceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("No column name matches target schema(col1::varchar, col2::int32)"));
      Assert.assertTrue(e.getMessage().contains("src1.csv"));
    }
    Assert.assertTrue("Expected exception not thrown", exceptionThrown);
    Assert.assertTrue(newSourceFile.delete());

    relativePath = String.format("/store/json/copyintosource/%s", "no_column_match.json");
    location = createTempLocation();
    newSourceFile = new File(location.toString(), "src1.json");
    oldSourceFile = FileUtils.getResourceAsFile(relativePath);
    Files.copy(oldSourceFile, newSourceFile);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'json\'", TEMP_SCHEMA, "target1", storageLocation);
    exceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      Assert.assertTrue(e.getMessage().contains("No column name matches target schema(col1::varchar, col2::int32)"));
      Assert.assertTrue(e.getMessage().contains("src1.json"));
      Assert.assertTrue(e.getMessage().contains("Line: 7, Record: 2"));
      exceptionThrown = true;
    }
    Assert.assertTrue("Expected exception not thrown", exceptionThrown);
    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, "target1");
    test(dropQuery);
  }

  public static void testCSVWithFilePatternDoubleType(BufferAllocator allocator, String source) throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTableWithDoubles(source, 2, 5, 0);
         DmlQueryTestUtils.Table sourceTable1 = createBasicTableWithDoubles(source, 2, 2, 5);
         DmlQueryTestUtils.Table sourceTable2 = createBasicTableWithDoubles(source, 2, 4, 7)) {

      testCSVWithFilter(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2},
        "copy INTO %s FROM '%s' REGEX '.*.csv' (RECORD_DELIMITER '\n', FIELD_DELIMITER ',', ON_ERROR 'ABORT_COPY')");
    }
  }

  public static void testCSVWithFilePatternFloatType(BufferAllocator allocator, String source) throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTableWithFloats(source, 2, 5, 0);
         DmlQueryTestUtils.Table sourceTable1 = createBasicTableWithFloats(source, 2, 2, 5);
         DmlQueryTestUtils.Table sourceTable2 = createBasicTableWithFloats(source, 2, 4, 7)) {

      testCSVWithFilter(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2},
        "copy INTO %s FROM '%s' REGEX '.*.csv' (RECORD_DELIMITER '\n', FIELD_DELIMITER ',', ON_ERROR 'ABORT_COPY')");
    }
  }

  public static void testCSVWithFilePatternDecimalType(BufferAllocator allocator, String source) throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTableWithDecimals(source, 2, 5, 0);
         DmlQueryTestUtils.Table sourceTable1 = createBasicTableWithDecimals(source, 2, 2, 5);
         DmlQueryTestUtils.Table sourceTable2 = createBasicTableWithDecimals(source, 2, 4, 7)) {

      testCSVWithFilter(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2},
        "copy INTO %s FROM '%s' REGEX '.*.csv' (RECORD_DELIMITER '\n', FIELD_DELIMITER ',', ON_ERROR 'ABORT_COPY')");
    }
  }

  public static void testCSVWithDateType(BufferAllocator allocator, String source) throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTableWithDates(source, 2, 5, 0, "2000-01-01", "YYYY-MM-DD");
         DmlQueryTestUtils.Table sourceTable1 = createBasicTableWithDates(source, 2, 2, 5, "2000-01-01", "YYYY-MM-DD");
         DmlQueryTestUtils.Table sourceTable2 = createBasicTableWithDates(source, 2, 4, 7, "2000-01-01", "YYYY-MM-DD")) {
      testCSVWithFilter(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2},
        "COPY INTO %s FROM '%s' REGEX '.*.csv' (DATE_FORMAT 'YYYY-MM-DD\"T\"HH24:MI:SS.FFF', RECORD_DELIMITER '\n', FIELD_DELIMITER ',', ON_ERROR 'ABORT_COPY')");
    }
  }

  public static void testRegexOnPath(BufferAllocator allocator, String source) throws Exception {
    String ctasQuery = String.format("CREATE TABLE %s.%s (%s)", TEMP_SCHEMA, "target1", "col1 varchar");
    test(ctasQuery);
    String jsonPath = String.format("/store/json/copyintosource/%s", "onecolumn.json");
    String csvPath = String.format("/store/json/copyintosource/%s", "onecolumn.csv");

    File location = createTempLocation();
    File tempdir = new File(location.toString(), "pathfolder/dir1/");
    tempdir.mkdirs();
    File dir1 = new File(tempdir, "file1.json");
    tempdir = new File(location.toString(), "pathfolder/dir2/");
    tempdir.mkdirs();
    File dir2 = new File(tempdir, "file2.csv");
    tempdir = new File(location.toString(), "pathfolder/abc1/");
    tempdir.mkdirs();
    File abc1 = new File(tempdir, "file3.json");

    File jsonFile = FileUtils.getResourceAsFile(jsonPath);
    File csvFile = FileUtils.getResourceAsFile(csvPath);
    Files.copy(jsonFile, dir1);
    Files.copy(csvFile, dir2);
    Files.copy(jsonFile, abc1);

    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String folderFiltering = String.format("COPY INTO %s.%s FROM %s REGEX \'.*/dir.*/.*\\.json\' FILE_FORMAT \'json\'", TEMP_SCHEMA, "target1", storageLocation);
    test(folderFiltering);
    new TestBuilder(allocator)
            .sqlQuery("SELECT col1 FROM %s.%s", TEMP_SCHEMA, "target1")
            .unOrdered()
            .baselineColumns("col1")
            .baselineValues("jsonfile")
            .go();

    folderFiltering = String.format("COPY INTO %s.%s FROM %s REGEX \'.*\\.json\' FILE_FORMAT \'json\'", TEMP_SCHEMA, "target1", storageLocation);
    test(folderFiltering);
    new TestBuilder(allocator)
            .sqlQuery("SELECT col1 FROM %s.%s", TEMP_SCHEMA, "target1")
            .unOrdered()
            .baselineColumns("col1")
            .baselineValues("jsonfile")
            .baselineValues("jsonfile")
            .baselineValues("jsonfile")
            .go();

    folderFiltering = String.format("COPY INTO %s.%s FROM %s REGEX \'.*\\.csv\' FILE_FORMAT \'csv\' (RECORD_DELIMITER '\n' , QUOTE_CHAR '|')", TEMP_SCHEMA, "target1", storageLocation);
    test(folderFiltering);
    new TestBuilder(allocator)
            .sqlQuery("SELECT col1 FROM %s.%s", TEMP_SCHEMA, "target1")
            .unOrdered()
            .baselineColumns("col1")
            .baselineValues("jsonfile")
            .baselineValues("jsonfile")
            .baselineValues("jsonfile")
            .baselineValues("csvfile")
            .go();

    tempdir = new File(location.toString(), location.getPath() + "/" + "abc");
    tempdir.mkdirs();
    dir1 = new File(tempdir, "file4.json");
    Files.copy(jsonFile, dir1);

    folderFiltering = String.format("COPY INTO %s.%s FROM %s REGEX \'.*/abc/.*\\.json\' FILE_FORMAT \'json\'", TEMP_SCHEMA, "target1", storageLocation);
    test(folderFiltering);
    new TestBuilder(allocator)
            .sqlQuery("SELECT col1 FROM %s.%s", TEMP_SCHEMA, "target1")
            .unOrdered()
            .baselineColumns("col1")
            .baselineValues("jsonfile")
            .baselineValues("jsonfile")
            .baselineValues("jsonfile")
            .baselineValues("csvfile")
            .baselineValues("jsonfile")
            .go();

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, "target1");
    test(dropQuery);
  }

  public static void testJSONWithDateType(BufferAllocator allocator, String source) throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTableWithDates(source, 2, 5, 0, "2000-01-01", "YYYY-MM-DD");
         DmlQueryTestUtils.Table sourceTable1 = createBasicTableWithDates(source, 2, 2, 5, "2000-01-01", "YYYY-MM-DD");
         DmlQueryTestUtils.Table sourceTable2 = createBasicTableWithDates(source, 2, 4, 7, "2000-01-01", "YYYY-MM-DD")) {
      testJSON(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2},
        "COPY INTO %s FROM '%s' REGEX '.*.json' (DATE_FORMAT 'YYYY-MM-DD\"T\"HH24:MI:SS.FFF', ON_ERROR 'ABORT_COPY')");
    }
  }

  public static void testJSONWithListOfDates(BufferAllocator allocator, String source) throws Exception {
    // Here, targetTable, sourceTable1 and sourceTable2 will be empty i.e. originalData will not contain anything. This is because, for now,
    // we do not support INSERT INTO with complex data types like List and Struct.
    try(DmlQueryTestUtils.Table targetTable = createEmptyTableWithListOfType(source, 2, SqlTypeName.DATE.getName());
        DmlQueryTestUtils.Table sourceTable1Empty = createEmptyTableWithListOfType(source, 2, SqlTypeName.DATE.getName());
        DmlQueryTestUtils.Table sourceTable2Empty = createEmptyTableWithListOfType(source, 2, SqlTypeName.DATE.getName())) {
      // We need to initialise sourceTable1 and sourceTable2 manually with sample data, which will be used further ahead to prepare JSON files for COPY-INTO.
      // Initialise sourceTable1Empty with 5 records.
      DmlQueryTestUtils.Table sourceTable1 = initialiseTableWithListOfDate(sourceTable1Empty, 5, 2, 0, "2000-01-01", "YYYY-MM-DD");
      // Initialise sourceTable2 with 3 records.
      DmlQueryTestUtils.Table sourceTable2 = initialiseTableWithListOfDate(sourceTable2Empty, 3, 2, 6, "2010-10-15", "YYYY-MM-DD");
      testJSON(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2},
        "COPY INTO %s FROM '%s' REGEX '.*.json' (DATE_FORMAT 'YYYY-MM-DD\"T\"HH24:MI', ON_ERROR 'ABORT_COPY')");
    }
  }

  private static DmlQueryTestUtils.Table initialiseTableWithListOfDate(DmlQueryTestUtils.Table table, int rowCount, int columnCount, int startingRowId, String startDateString, String dateTimeFormat) {
    Object[][] data = new Object[rowCount][columnCount];
    java.time.LocalDate startDate = java.time.LocalDate.parse(startDateString, DateTimeFormatter.ISO_LOCAL_DATE); // Using ISO_LOCAL_DATE here as it corresponds to our default "YYYY-MM-DD" format.
    java.time.LocalDateTime startDateTime = LocalDateTime.of(startDate, LocalTime.MIDNIGHT); // For this test, time is represented in the format "yyyy-MM-dd\"T\"HH:mm". (Format string is according to Java.time API)
    for (int r = startingRowId; r < startingRowId + rowCount; r++) {
      data[r - startingRowId][0] = r;
      for (int c = 0; c < columnCount - 1; c++) {
        List<LocalDateTime> entryList = new JsonStringArrayList<>();
        // If the current row index is n, insert n + 1 records
        int n = r - startingRowId;
        for(int i = 0; i < (n + 1); i++) {
          entryList.add(startDateTime.plusDays(r+c+1));
        }
        data[r - startingRowId][c + 1] = entryList;
      }
    }
    return new DmlQueryTestUtils.Table(table.name, table.paths, table.fqn, table.columns, data);
  }
  public static void testFilesKeywordWithCSV(BufferAllocator allocator, String source) throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTableWithDates(source, 2, 5, 0, "2000-01-01", "YYYY-MM-DD");
         DmlQueryTestUtils.Table sourceTable1 = createBasicTableWithDates(source, 2, 2, 5, "2000-01-01", "YYYY-MM-DD");
         DmlQueryTestUtils.Table sourceTable2 = createBasicTableWithDates(source, 2, 4, 7, "2000-01-01", "YYYY-MM-DD")) {
      testCopyCSVFiles(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{ sourceTable2},
        "COPY INTO %s FROM '%s' FILES('%s') (DATE_FORMAT 'YYYY-MM-DD\"T\"HH24:MI:SS.FFF', RECORD_DELIMITER '\n', FIELD_DELIMITER ',', ON_ERROR 'ABORT_COPY')", false);
    }
  }

  public static void testFilesWithCSVFilesAtImmediateSource(BufferAllocator allocator, String source) throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTableWithDates(source, 2, 5, 0, "2000-01-01", "YYYY-MM-DD");
         DmlQueryTestUtils.Table sourceTable1 = createBasicTableWithDates(source, 2, 2, 5, "2000-01-01", "YYYY-MM-DD");
         DmlQueryTestUtils.Table sourceTable2 = createBasicTableWithDates(source, 2, 4, 7, "2000-01-01", "YYYY-MM-DD")) {
      testCopyCSVFiles(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{ sourceTable2},
        "COPY INTO %s FROM '%s' FILES('%s') (DATE_FORMAT 'YYYY-MM-DD\"T\"HH24:MI:SS.FFF', RECORD_DELIMITER '\n', FIELD_DELIMITER ',', ON_ERROR 'ABORT_COPY')", true);
    }
  }

  public static void testCSVWithStringDelimiter(BufferAllocator allocator, String source) throws Exception {
    try (DmlQueryTestUtils.Table targetTable = createBasicTable(0, source, 2, 5);
         DmlQueryTestUtils.Table sourceTable1 = createBasicTable(5, source, 2, 2, "f1");
         DmlQueryTestUtils.Table sourceTable2 = createBasicTable(7, source, 2, 4, "f2")) {
      testCSVWithFilter(allocator, source, targetTable, new DmlQueryTestUtils.Table[]{sourceTable1, sourceTable2}, new DmlQueryTestUtils.Table[]{sourceTable1},
              "copy INTO %s FROM '%s' REGEX '.*1.csv' (RECORD_DELIMITER '\\n', FIELD_DELIMITER ',', ON_ERROR 'ABORT_COPY')");
    }
  }

  public static void testJSONComplex(BufferAllocator allocator, String source) throws Exception {
    testSource(allocator, source, "col1 array<struct<c1 int, c2 float, c3 double>>", "source1.json", "col1[0]['c1']", 1231, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array<struct<c1: int, c2: array<int>, c3: double>>, col2 varchar", "source2.json", "col1[2]['c2'][3]", 64, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array< array<int>>, col3 varchar", "source3.json", "col1[2][1]", 100, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array< array<struct<c1: int, c2: int, c3: varchar>>>, col3 varchar", "source4.json", "col1[1][0]['c2']", 5, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array< array<struct<c1: int, c2: int, c3: varchar>>>, col3 varchar, col4 int, col5 struct<c1: int, c2: int>", "source4.json", "col5['c2']", 100, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 struct< col2: array<int>, col3: varchar>", "source5.json", "col1['col2'][0]", 10, JSON_SOURCE_FOLDER);
  }

  public static void testJSONComplexNullValues(BufferAllocator allocator, String source) throws Exception {
    testSource(allocator, source, "col1 array<struct<c1 int, c2 float, c3 double>>", "source1.json", "col1[1]['c3']", null, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array<struct<c1: int, c2: array<int>, c3: double>>, col2 varchar", "source2.json", "col1[2]['c2'][2]", null, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array<struct<c1: int, c2: array<int>, c3: double>>, col2 varchar", "source2.json", "col1[0]['c2'][2]", null, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array<struct<c1: int, c2: array<int>, c3: double>>, col2 varchar", "source2.json", "col1[0]", null, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array< array<int>>, col3 varchar", "source3.json", "col1[3][1]", null, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array< array<int>>, col3 varchar", "source3.json", "col1[3]", null, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array< array<struct<c1: int, c2: int, c3: varchar>>>, col3 varchar, col4 int, col5 struct<c1: int, c2: varchar>", "source4.json", "col1[2][1]['c2']", null, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array< array<struct<c1: int, c2: int, c3: varchar>>>, col3 varchar, col4 int, col5 struct<c1: int, c2: varchar>", "source4.json", "col4", null, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 array< array<struct<c1: int, c2: int, c3: varchar>>>, col3 varchar, col4 int, col5 struct<c1: int, c2: varchar>", "source4.json", "col5['c1']", null, JSON_SOURCE_FOLDER);
    testSource(allocator, source, "col1 struct< col2: array<int>, col3: varchar>", "source5.json", "col1['col2'][3]", null, JSON_SOURCE_FOLDER);
  }

  public static void testSource(BufferAllocator allocator, String source, String tableDef, String sourceFile, String columnSelect, Object expectValue, String relativeBaseFolderPath) throws Exception {
    String tableName = "target1" + (int)(Math.random() * 1_000_00);
    String ctasQuery = String.format("CREATE TABLE %s.%s (%s)", TEMP_SCHEMA, tableName, tableDef);
    test(ctasQuery);
    String relativePath = relativeBaseFolderPath + sourceFile;
    File location = createTempLocation();
    File newSourceFile = new File(location.toString(), source);
    File oldSourceFile = FileUtils.getResourceAsFile(relativePath);
    Files.copy(oldSourceFile, newSourceFile);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String fileExtension = sourceFile.toLowerCase().endsWith("json")? "json" : "csv";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'%s\'", TEMP_SCHEMA, tableName, storageLocation, fileExtension);
    if (fileExtension.equals("csv")) {
      copyIntoQuery += " (RECORD_DELIMITER '\n')";
    }
    test(copyIntoQuery);
    new TestBuilder(allocator)
            .sqlQuery("SELECT %s as column1 FROM %s.%s LIMIT 1", columnSelect, TEMP_SCHEMA, tableName)
            .unOrdered()
            .baselineColumns("column1")
            .baselineValues(expectValue)
            .go();
    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testEmptyFiles(BufferAllocator allocator, String source) throws Exception {
    String tableName = "tempty";
    String fileName =  "empty.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .expectsEmptyResultSet()
      .go();

    Assert.assertTrue(newSourceFile.delete());

    fileName =  "empty.json";
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int", fileName , location, false);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .expectsEmptyResultSet()
      .go();

    Assert.assertTrue(newSourceFile.delete());

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }


  public static void testOneColumnOneData(BufferAllocator allocator, String source) throws Exception {
    String tableName = "oneColumnOneData";
    String fileName =  "oneColumnOneData.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "oneCol varchar", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("oneCol")
      .baselineValues("str")
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testOneColumnNoData(String source) throws Exception {
    String tableName = "oneColumnNoData";
    String fileName =  "oneColumnNoData.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "oneCol varchar", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);

    boolean isExceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("Only one data line detected. Please consider changing line delimiter"));
    }

    Assert.assertTrue("No exception thrown",isExceptionThrown);

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testManyColumnsWhitespaceData(BufferAllocator allocator, String source) throws Exception {
    String tableName = "manyColumnsWhitespaceData";
    String fileName =  "manyColumnsWhitespaceData.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 varchar,c2 varchar,c3 varchar,c4 varchar,c5 varchar,c6 varchar,c7 varchar,c8 varchar,c9 varchar,c10 varchar", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10")
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", null)
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", null)
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", null)
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", null)
      .go();

    Assert.assertTrue(newSourceFile.delete());

    fileName =  "manyColumnsWhtespaceData.json";
    location = createTempLocation();
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 varchar,c2 varchar,c3 varchar,c4 varchar,c5 varchar,c6 varchar,c7 varchar,c8 varchar,c9 varchar,c10 varchar", fileName , location, false);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10")
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", null)
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", null)
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", null)
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", null)
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", " ")
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", " ")
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", " ")
      .baselineValues(" ", " ", " ", " ", " ", " ", " ", " ", " ", " ")
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testManyColumnsEmptyData(BufferAllocator allocator, String source) throws Exception {
    String tableName = "manyColumnsEmptyData";
    String fileName =  "manyColumnsEmptyData.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 varchar,c2 varchar,c3 varchar,c4 varchar,c5 varchar,c6 varchar,c7 varchar,c8 varchar,c9 varchar,c10 varchar", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10")
      .baselineValues(null, null, null, null, null, null, null, null, null, null)
      .baselineValues(null, null, null, null, null, null, null, null, null, null)
      .baselineValues(null, null, null, null, null, null, null, null, null, null)
      .baselineValues(null, null, null, null, null, null, null, null, null, null)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testManyColumnsWhitespaceTabData(BufferAllocator allocator, String source) throws Exception {
    String tableName = "manyColumnsWhitespaceTabData";
    String fileName =  "manyColumnsWhitespaceTabData.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 varchar,c2 varchar,c3 varchar,c4 varchar,c5 varchar,c6 varchar,c7 varchar,c8 varchar,c9 varchar,c10 varchar", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10")
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", null)
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", null)
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", null)
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", null)
      .go();

    Assert.assertTrue(newSourceFile.delete());

    fileName = "manyColumnsWhitespaceTabData.json";
    location = createTempLocation();
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 varchar,c2 varchar,c3 varchar,c4 varchar,c5 varchar,c6 varchar,c7 varchar,c8 varchar,c9 varchar,c10 varchar", fileName , location, false);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10")
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", null)
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", null)
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", null)
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", null)
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ")
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ")
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ")
      .baselineValues("   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ", "   ")
      .go();

    Assert.assertTrue(newSourceFile.delete());

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testNoHeaderOnlyData(BufferAllocator allocator, String source) throws Exception {
    String tableName = "noHeaderOnlyData";
    String fileName =  "noHeaderOnlyData.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 varchar,c2 varchar,c3 varchar,c4 varchar,c5 varchar,c6 varchar,c7 varchar,c8 varchar,c9 varchar,c10 varchar", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);

    boolean isExceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("No column name matches target schema"));
    }

    Assert.assertTrue("No exception thrown",isExceptionThrown);

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testMoreValuesThanHeaders(BufferAllocator allocator, String source) throws Exception {
    String tableName = "moreValuesThanHeaders";
    String fileName =  "moreValuesThanHeaders.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int,c4 int", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3", "c4")
      .baselineValues(1,2,3,4)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);

    tableName = "moreValuesThanHeaders2";
    location = createTempLocation();
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int,c4 int, c5 int", fileName , location, true);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3", "c4","c5")
      .baselineValues(1,2,3,4,null)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testMoreHeadersThanValues(BufferAllocator allocator, String source) throws Exception {
    String tableName = "moreHeadersThanValues";
    String fileName =  "moreHeadersThanValues.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int,c4 int,c5 int", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3", "c4", "c5")
      .baselineValues(1,2,3,4, null)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testColumnsTableUppercaseFileLowerCase(BufferAllocator allocator, String source) throws Exception {
    String tableName = "tableUppercaseFileLowerCase";
    String fileName =  "smallCaseColumns.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "C1 int,C2 int,C3 int", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("C1", "C2", "C3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    Assert.assertTrue(newSourceFile.delete());

    fileName =  "smallCaseColumns.json";
    location = createTempLocation();
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "C1 int,C2 int,C3 int", fileName , location, false);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("C1", "C2", "C3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testColumnsTableLowercaseFileUppercase(BufferAllocator allocator, String source) throws Exception {
    String tableName = "tableLowercaseFileUppercase";
    String fileName =  "upperCaseColumns.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    Assert.assertTrue(newSourceFile.delete());

    fileName =  "upperCaseColumns.json";
    location = createTempLocation();
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int", fileName , location, false);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testManyColumnsNoValues(BufferAllocator allocator, String source) throws Exception {
    String tableName = "manyColumnsNoValues";
    String fileName =  "manyColumnsNoValues.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int,c4 int,c5 int,c6 int", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);

    boolean isExceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("Only one data line detected. Please consider changing line delimiter"));
    }

    Assert.assertTrue("No exception thrown",isExceptionThrown);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .expectsEmptyResultSet()
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testNullValueAtRootLevel(BufferAllocator allocator, String source) throws Exception {
    String tableName = "nullValueAtRootLevel";
    String fileName =  "nullValueAtRootLevel.json";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "col1 int, col2 varchar", fileName , location, false);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("col1", "col2")
      .baselineValues(56,"abc")
      .baselineValues(null, null)
      .baselineValues(23, null)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testValuesInSingleQuotes(BufferAllocator allocator, String source) throws Exception {
    String tableName = "valuesInSingleQuotesVarchar";
    String fileName =  "valuesInSingleQuotes.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 varchar,c2 varchar,c3 varchar", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3")
      .baselineValues("\'1\'","\'2\'","\'3\'")
      .baselineValues("\'4\'","\'5\'","\'6\'")
      .baselineValues("\'7\'","\'8\'","\'9\'")
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testValuesInDoubleQuotes(BufferAllocator allocator, String source) throws Exception {
    String tableName = "valuesInDoubleQuotesVarchar";
    String fileName =  "valuesInDoubleQuotes.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 varchar,c2 varchar,c3 varchar", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3")
      .baselineValues("1","2","3")
      .baselineValues("4","5","6")
      .baselineValues("7","8","9")
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);

    tableName = "valuesInDoubleQuotesInt";
    location = createTempLocation();
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int", fileName , location, true);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3")
      .baselineValues(1,2,3)
      .baselineValues(4,5,6)
      .baselineValues(7,8,9)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testBooleanCaseSensitivity(BufferAllocator allocator, String source) throws Exception {
    String tableName = "booleanCaseSensitivity";
    String fileName =  "boolColLowercase.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "id int,boolCol boolean", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT boolCol FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("boolCol")
      .baselineValues(true)
      .baselineValues(true)
      .baselineValues(false)
      .go();

    Assert.assertTrue(newSourceFile.delete());

    fileName =  "boolColUppercase.csv";
    location = createTempLocation();
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "id int,boolCol boolean", fileName , location, true);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT boolCol FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("boolCol")
      .baselineValues(true)
      .baselineValues(true)
      .baselineValues(false)
      .baselineValues(true)
      .baselineValues(true)
      .baselineValues(false)
      .go();

    Assert.assertTrue(newSourceFile.delete());

    fileName =  "boolColMixedcase.csv";
    location = createTempLocation();
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "id int,boolCol boolean", fileName , location, true);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT boolCol FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("boolCol")
      .baselineValues(true)
      .baselineValues(true)
      .baselineValues(false)
      .baselineValues(true)
      .baselineValues(true)
      .baselineValues(false)
      .baselineValues(true)
      .baselineValues(true)
      .baselineValues(false)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testHeaderEmptyString(BufferAllocator allocator, String source) throws Exception {
    String tableName = "headerEmptyString";
    String fileName =  "headerEmptyString.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testFileExtensionCaseSensitivity(BufferAllocator allocator, String source) throws Exception {
    String tableName = "fileExtensionCaseSensitivity";
    String fileName =  "fileExtensionCaseSensitivity.CSV";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    Assert.assertTrue(newSourceFile.delete());

    fileName =  "fileExtensionCaseSensitivity.JSON";
    location = createTempLocation();
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int", fileName , location, false);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testDifferentExtensions(BufferAllocator allocator, String source) throws Exception {
    String tableName = "tdiffFormats";
    File location = createTempLocation();
    File tempdir = new File(location.toString(), "diffFormats/");
    tempdir.mkdirs();

    String ctasQuery = String.format("CREATE TABLE %s.%s (%s)", TEMP_SCHEMA, tableName, "c1 int, c2 int, c3 int");
    test(ctasQuery);

    String fileName = "diffFormats/f1.txt";
    File newSourceFile1 = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int", fileName , location, true);

    fileName = "diffFormats/f1.dat";
    File newSourceFile2 = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int", fileName , location, true);

    fileName = "diffFormats/f1.csv";
    File newSourceFile3 = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int", fileName , location, true);

    String storageLocation = "\'@" + source + "/" + location.getName() + "/" + tempdir.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT \'csv\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation);
    test(copyIntoQuery);
    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1","c2","c3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    Assert.assertTrue(newSourceFile1.delete());
    Assert.assertTrue(newSourceFile2.delete());
    Assert.assertTrue(newSourceFile3.delete());
    Assert.assertTrue(tempdir.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }


  public static void testComplexValuesCsv(BufferAllocator allocator, String source) throws Exception {
    String tableName = "tStructValues";
    String fileName =  "structValues.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "id int, ooa struct<c1: int, c2 int, c3 varchar>, ooa2 array<int>", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    boolean isExceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("Unsupported data type : Struct"));
    }

    Assert.assertTrue("No exception thrown",isExceptionThrown);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .expectsEmptyResultSet()
      .go();

    Assert.assertTrue(newSourceFile.delete());

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);

    tableName = "tListValues";
    fileName =  "listValues.csv";
    location = createTempLocation();
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "id int, ooa array<int>", fileName , location, true);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    isExceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("Unsupported data type : List"));
    }

    Assert.assertTrue("No exception thrown",isExceptionThrown);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .expectsEmptyResultSet()
      .go();

    Assert.assertTrue(newSourceFile.delete());
    dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testRegexCaseSensitivity(BufferAllocator allocator, String source) throws Exception {
    String tableName = "caseSensitivity";
    String fileName =  "caseSensitivity.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 int,c2 int,c3 int", fileName, location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'CASESENSITIVITY\' FILE_FORMAT 'csv' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .expectsEmptyResultSet()
      .go();

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'(?i)CASESENSITIVITY\' FILE_FORMAT 'csv' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1","c2","c3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    Assert.assertTrue(newSourceFile.delete());

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testComplexTypesToVarcharCoercion(String source) throws Exception {
    String tableName = "complexToVarchar";
    String fileName =  "list.json";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "ooa varchar", fileName , location, false);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    boolean isExceptionThrown = false;

    try {
      test(copyIntoQuery);
    } catch (RuntimeException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("List datatype in the file cannot be coerced into Utf8 datatype of the target table"));
    }

    Assert.assertTrue( "No exception thrown", isExceptionThrown);
    Assert.assertTrue(newSourceFile.delete());

    fileName =  "struct.json";
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "ooa varchar", fileName , location, false);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    isExceptionThrown = false;

    try {
      test(copyIntoQuery);
    } catch (RuntimeException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("Struct datatype in the file cannot be coerced into Utf8 datatype of the target table"));
    }

    Assert.assertTrue( "No exception thrown", isExceptionThrown);
    Assert.assertTrue(newSourceFile.delete());

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testNullIf(BufferAllocator allocator, String source) throws Exception {
    String tableName = "tnullIf";
    String fileName =  "nullIf.csv";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 varchar,c2 varchar,c3 varchar", fileName , location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n', NULL_IF ('None', 'NA'))", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3")
      .baselineValues(null,"dremio","yes")
      .baselineValues("dremio",null,"yes")
      .baselineValues("dremio","yes",null)
      .go();

    Assert.assertTrue(newSourceFile.delete());

    fileName =  "nullIf.json";
    location = createTempLocation();
    newSourceFile = createTableAndGenerateSourceFiles(tableName, "c1 varchar,c2 varchar,c3 varchar", fileName , location, false);
    storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n', NULL_IF ('None', 'NA'))", TEMP_SCHEMA, tableName, storageLocation, fileName);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1", "c2", "c3")
      .baselineValues(null,"dremio","yes")
      .baselineValues("dremio",null,"yes")
      .baselineValues("dremio","yes",null)
      .baselineValues(null,"dremio","yes")
      .baselineValues("dremio",null,"yes")
      .baselineValues("dremio","yes",null)
      .go();

    Assert.assertTrue(newSourceFile.delete());
    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static void testIntegerCoercion(BufferAllocator allocator, String source) throws Exception {
    File location = createTempLocation();

    String fileName1 =  "Integer.csv";
    String relativePath1 = String.format("/store/text/copyintosource/%s", fileName1);
    File newSourceFile1 = new File(location.toString(), fileName1);
    File oldSourceFile1 = FileUtils.getResourceAsFile(relativePath1);
    Files.copy(oldSourceFile1, newSourceFile1);

    String fileName2 = "Integer.json";
    String relativePath2 = String.format("/store/json/copyintosource/%s", fileName2);
    File newSourceFile2 = new File(location.toString(), fileName2);
    File oldSourceFile2 = FileUtils.getResourceAsFile(relativePath2);
    Files.copy(oldSourceFile2, newSourceFile2);

    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";

    String tableName = "tInt";
    String schema = "id int";
    String ctasQuery = String.format("CREATE TABLE IF NOT EXISTS %s.%s (%s)", TEMP_SCHEMA, tableName, schema);
    test(ctasQuery);

    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName1);
    test(copyIntoQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("id")
      .baselineValues(1)
      .baselineValues(2)
      .baselineValues(1)
      .baselineValues(2)
      .go();

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);

    tableName = "tBigint";
    schema = "id bigint";
    ctasQuery = String.format("CREATE TABLE IF NOT EXISTS %s.%s (%s)", TEMP_SCHEMA, tableName, schema);
    test(ctasQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName1);
    test(copyIntoQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("id")
      .baselineValues(1L)
      .baselineValues(2L)
      .baselineValues(1L)
      .baselineValues(2L)
      .go();

    dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);

    tableName = "tFloat";
    schema = "id float";
    ctasQuery = String.format("CREATE TABLE IF NOT EXISTS %s.%s (%s)", TEMP_SCHEMA, tableName, schema);
    test(ctasQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName1);
    test(copyIntoQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("id")
      .baselineValues(2f)
      .baselineValues(1f)
      .baselineValues(2f)
      .baselineValues(1f)
      .go();

    dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);

    tableName = "tDouble";
    schema = "id double";
    ctasQuery = String.format("CREATE TABLE IF NOT EXISTS %s.%s (%s)", TEMP_SCHEMA, tableName, schema);
    test(ctasQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName1);
    test(copyIntoQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("id")
      .baselineValues(1.0d)
      .baselineValues(2.0d)
      .baselineValues(1.0d)
      .baselineValues(2.0d)
      .go();

    dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);

    tableName = "tDecimal";
    schema = "id decimal(10,2)";
    ctasQuery = String.format("CREATE TABLE IF NOT EXISTS %s.%s (%s)", TEMP_SCHEMA, tableName, schema);
    test(ctasQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName1);
    test(copyIntoQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("id")
      .baselineValues(BigDecimal.valueOf(1.00))
      .baselineValues(BigDecimal.valueOf(2.00))
      .baselineValues(BigDecimal.valueOf(1.00))
      .baselineValues(BigDecimal.valueOf(2.00))
      .go();

    dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
    Assert.assertTrue(newSourceFile1.delete());
    Assert.assertTrue(newSourceFile2.delete());
  }

  public static void testNumberCoercion(BufferAllocator allocator, String source) throws Exception {
    File location = createTempLocation();

    String fileName1 =  "Number.csv";
    String relativePath1 = String.format("/store/text/copyintosource/%s", fileName1);
    File newSourceFile1 = new File(location.toString(), fileName1);
    File oldSourceFile1 = FileUtils.getResourceAsFile(relativePath1);
    Files.copy(oldSourceFile1, newSourceFile1);

    String fileName2 = "Number.json";
    String relativePath2 = String.format("/store/json/copyintosource/%s", fileName2);
    File newSourceFile2 = new File(location.toString(), fileName2);
    File oldSourceFile2 = FileUtils.getResourceAsFile(relativePath2);
    Files.copy(oldSourceFile2, newSourceFile2);

    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";

    String tableName = "tInt";
    String schema = "id int";
    String ctasQuery = String.format("CREATE TABLE IF NOT EXISTS %s.%s (%s)", TEMP_SCHEMA, tableName, schema);
    test(ctasQuery);

    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName1);

    boolean isExceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("Could not convert \"1.10\" to INT"));
    }
    Assert.assertTrue("No exception thrown",isExceptionThrown);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    isExceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("Could not convert \"1.10\" to INT"));
    }
    Assert.assertTrue("No exception thrown",isExceptionThrown);

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);

    tableName = "tBigint";
    schema = "id bigint";
    ctasQuery = String.format("CREATE TABLE IF NOT EXISTS %s.%s (%s)", TEMP_SCHEMA, tableName, schema);
    test(ctasQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName1);
    isExceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("Could not convert \"1.10\" to BIGINT"));
    }
    Assert.assertTrue("No exception thrown",isExceptionThrown);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    isExceptionThrown = false;
    try {
      test(copyIntoQuery);
    } catch (UserException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("Could not convert \"1.10\" to BIGINT"));
    }
    Assert.assertTrue("No exception thrown",isExceptionThrown);

    dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);

    tableName = "tFloat";
    schema = "id float";
    ctasQuery = String.format("CREATE TABLE IF NOT EXISTS %s.%s (%s)", TEMP_SCHEMA, tableName, schema);
    test(ctasQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName1);
    test(copyIntoQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("id")
      .baselineValues(2.20f)
      .baselineValues(1.10f)
      .baselineValues(2.20f)
      .baselineValues(1.10f)
      .go();

    dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);

    tableName = "tDouble";
    schema = "id double";
    ctasQuery = String.format("CREATE TABLE IF NOT EXISTS %s.%s (%s)", TEMP_SCHEMA, tableName, schema);
    test(ctasQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName1);
    test(copyIntoQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("id")
      .baselineValues(1.10d)
      .baselineValues(2.20d)
      .baselineValues(1.10d)
      .baselineValues(2.20d)
      .go();

    dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);

    tableName = "tDecimal";
    schema = "id decimal(10,2)";
    ctasQuery = String.format("CREATE TABLE IF NOT EXISTS %s.%s (%s)", TEMP_SCHEMA, tableName, schema);
    test(ctasQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName1);
    test(copyIntoQuery);

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("id")
      .baselineValues(BigDecimal.valueOf(1.10))
      .baselineValues(BigDecimal.valueOf(2.20))
      .baselineValues(BigDecimal.valueOf(1.10))
      .baselineValues(BigDecimal.valueOf(2.20))
      .go();

    dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
    Assert.assertTrue(newSourceFile1.delete());
    Assert.assertTrue(newSourceFile2.delete());
  }

  public static void testColumnNameCaseSensitivity(BufferAllocator allocator, String source) throws Exception {
    String tableName = "columnNameCaseSensitivity";
    String fileName1 =  "columnCaseSensitivity.csv";
    String fileName2 = "columnCaseSensitivity.json";
    File location = createTempLocation();
    File newSourceFile1 = createTableAndGenerateSourceFiles(tableName, "col1 int, col2 int, col3 int", fileName1 , location, true);
    File newSourceFile2 = createTableAndGenerateSourceFiles(tableName, "col1 int, col2 int, col3 int", fileName2 , location, false);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName1);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("col1","col2","col3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("col1","col2","col3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
    Assert.assertTrue(newSourceFile1.delete());
    Assert.assertTrue(newSourceFile2.delete());
  }

  public static void testDifferentFieldDelimitersForCsv(BufferAllocator allocator, String source) throws Exception {
    String tableName = "testFieldDelimiters";
    String fileName1 = "fieldDelimiter1.csv";
    String fileName2 = "fieldDelimiter2.csv";
    String fileName3 = "fieldDelimiter3.csv";
    File location = createTempLocation();
    File newSourceFile1 = createTableAndGenerateSourceFiles(tableName, "c1 int, c2 int, c3 int", fileName1, location, true);
    File newSourceFile2 = createTableAndGenerateSourceFiles(tableName, "c1 int, c2 int, c3 int", fileName2, location, true);
    File newSourceFile3 = createTableAndGenerateSourceFiles(tableName, "c1 int, c2 int, c3 int", fileName3, location, true);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n', FIELD_DELIMITER ':')", TEMP_SCHEMA, tableName, storageLocation, fileName1);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1","c2","c3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n', FIELD_DELIMITER '|')", TEMP_SCHEMA, tableName, storageLocation, fileName2);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1","c2","c3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n', FIELD_DELIMITER '/')", TEMP_SCHEMA, tableName, storageLocation, fileName3);
    test(copyIntoQuery);

    new TestBuilder(allocator)
      .sqlQuery("SELECT * FROM %s.%s ", TEMP_SCHEMA, tableName)
      .unOrdered()
      .baselineColumns("c1","c2","c3")
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .baselineValues(1,2,3)
      .go();

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
    Assert.assertTrue(newSourceFile1.delete());
    Assert.assertTrue(newSourceFile2.delete());
    Assert.assertTrue(newSourceFile3.delete());
  }

  public static void testListOfListToListCoercion(String source) throws Exception {
    String tableName = "tArray";
    String fileName = "list_of_list.json";
    File location = createTempLocation();
    File newSourceFile = createTableAndGenerateSourceFiles(tableName, "ooa array<int>", fileName, location, false);
    String storageLocation = "\'@" + source + "/" + location.getName() + "\'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation, fileName);
    boolean isExceptionThrown = false;

    try {
      test(copyIntoQuery);
    } catch (RuntimeException e) {
      isExceptionThrown = true;
      Assert.assertTrue(e.getMessage().contains("List datatype in the file cannot be coerced into Int(32, true) datatype of the target table"));
    }

    Assert.assertTrue("No exception thrown", isExceptionThrown);
    Assert.assertTrue(newSourceFile.delete());

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  public static File createTableAndGenerateSourceFiles(String tableName, String schema, String fileName, File location, boolean isCsv) throws Exception {
    String ctasQuery = String.format("CREATE TABLE IF NOT EXISTS %s.%s (%s)", TEMP_SCHEMA, tableName, schema);
    test(ctasQuery);
    String relativePath = isCsv ? String.format("/store/text/copyintosource/%s", fileName) : String.format("/store/json/copyintosource/%s", fileName);
    File newSourceFile = new File(location.toString(), fileName);
    File oldSourceFile = FileUtils.getResourceAsFile(relativePath);
    Files.copy(oldSourceFile, newSourceFile);

    return newSourceFile;
  }

  private static void testFileCountEstimate(List<String> files) {
    long actual = CopyIntoTablePlanBuilder.getFileCountEstimate(files);

    if (files == null || files.isEmpty()) {
      Assert.assertEquals(CopyIntoTablePlanBuilder.DEFAULT_FILE_COUNT_ESTIMATE, actual);
    } else {
      Assert.assertEquals(files.size(), actual);
    }
  }

  @Test
  public void testFileCountEstimate() {
    testFileCountEstimate(null);

    testFileCountEstimate(Collections.emptyList());

    testFileCountEstimate(ImmutableList.of("f1"));

    testFileCountEstimate(ImmutableList.of("f1", "f2"));
  }

  @Test
  public void testLargeTable900Fields() throws Exception {
    String tableName = "largeTable900Fields";
    String cols = "(column_0 int, column_1 int, column_2 int, column_3 int, column_4 int, column_5 int, column_6 int, column_7 int, column_8 int, column_9 int, column_10 int, column_11 int, column_12 int, column_13 int, column_14 int, column_15 int, column_16 int, column_17 int, column_18 int, column_19 int, column_20 int, column_21 int, column_22 int, column_23 int, column_24 int, column_25 int, column_26 int, column_27 int, column_28 int, column_29 int, column_30 int, column_31 int, column_32 int, column_33 int, column_34 int, column_35 int, column_36 int, column_37 int, column_38 int, column_39 int, column_40 int, column_41 int, column_42 int, column_43 int, column_44 int, column_45 int, column_46 int, column_47 int, column_48 int, column_49 int, column_50 int, column_51 int, column_52 int, column_53 int, column_54 int, column_55 int, column_56 int, column_57 int, column_58 int, column_59 int, column_60 int, column_61 int, column_62 int, column_63 int, column_64 int, column_65 int, column_66 int, column_67 int, column_68 int, column_69 int, column_70 int, column_71 int, column_72 int, column_73 int, column_74 int, column_75 int, column_76 int, column_77 int, column_78 int, column_79 int, column_80 int, column_81 int, column_82 int, column_83 int, column_84 int, column_85 int, column_86 int, column_87 int, column_88 int, column_89 int, column_90 int, column_91 int, column_92 int, column_93 int, column_94 int, column_95 int, column_96 int, column_97 int, column_98 int, column_99 int, column_100 int, column_101 int, column_102 int, column_103 int, column_104 int, column_105 int, column_106 int, column_107 int, column_108 int, column_109 int, column_110 int, column_111 int, column_112 int, column_113 int, column_114 int, column_115 int, column_116 int, column_117 int, column_118 int, column_119 int, column_120 int, column_121 int, column_122 int, column_123 int, column_124 int, column_125 int, column_126 int, column_127 int, column_128 int, column_129 int, column_130 int, column_131 int, column_132 int, column_133 int, column_134 int, column_135 int, column_136 int, column_137 int, column_138 int, column_139 int, column_140 int, column_141 int, column_142 int, column_143 int, column_144 int, column_145 int, column_146 int, column_147 int, column_148 int, column_149 int, column_150 int, column_151 int, column_152 int, column_153 int, column_154 int, column_155 int, column_156 int, column_157 int, column_158 int, column_159 int, column_160 int, column_161 int, column_162 int, column_163 int, column_164 int, column_165 int, column_166 int, column_167 int, column_168 int, column_169 int, column_170 int, column_171 int, column_172 int, column_173 int, column_174 int, column_175 int, column_176 int, column_177 int, column_178 int, column_179 int, column_180 int, column_181 int, column_182 int, column_183 int, column_184 int, column_185 int, column_186 int, column_187 int, column_188 int, column_189 int, column_190 int, column_191 int, column_192 int, column_193 int, column_194 int, column_195 int, column_196 int, column_197 int, column_198 int, column_199 int, column_200 int, column_201 int, column_202 int, column_203 int, column_204 int, column_205 int, column_206 int, column_207 int, column_208 int, column_209 int, column_210 int, column_211 int, column_212 int, column_213 int, column_214 int, column_215 int, column_216 int, column_217 int, column_218 int, column_219 int, column_220 int, column_221 int, column_222 int, column_223 int, column_224 int, column_225 int, column_226 int, column_227 int, column_228 int, column_229 int, column_230 int, column_231 int, column_232 int, column_233 int, column_234 int, column_235 int, column_236 int, column_237 int, column_238 int, column_239 int, column_240 int, column_241 int, column_242 int, column_243 int, column_244 int, column_245 int, column_246 int, column_247 int, column_248 int, column_249 int, column_250 int, column_251 int, column_252 int, column_253 int, column_254 int, column_255 int, column_256 int, column_257 int, column_258 int, column_259 int, column_260 int, column_261 int, column_262 int, column_263 int, column_264 int, column_265 int, column_266 int, column_267 int, column_268 int, column_269 int, column_270 int, column_271 int, column_272 int, column_273 int, column_274 int, column_275 int, column_276 int, column_277 int, column_278 int, column_279 int, column_280 int, column_281 int, column_282 int, column_283 int, column_284 int, column_285 int, column_286 int, column_287 int, column_288 int, column_289 int, column_290 int, column_291 int, column_292 int, column_293 int, column_294 int, column_295 int, column_296 int, column_297 int, column_298 int, column_299 int, column_300 int, column_301 int, column_302 int, column_303 int, column_304 int, column_305 int, column_306 int, column_307 int, column_308 int, column_309 int, column_310 int, column_311 int, column_312 int, column_313 int, column_314 int, column_315 int, column_316 int, column_317 int, column_318 int, column_319 int, column_320 int, column_321 int, column_322 int, column_323 int, column_324 int, column_325 int, column_326 int, column_327 int, column_328 int, column_329 int, column_330 int, column_331 int, column_332 int, column_333 int, column_334 int, column_335 int, column_336 int, column_337 int, column_338 int, column_339 int, column_340 int, column_341 int, column_342 int, column_343 int, column_344 int, column_345 int, column_346 int, column_347 int, column_348 int, column_349 int, column_350 int, column_351 int, column_352 int, column_353 int, column_354 int, column_355 int, column_356 int, column_357 int, column_358 int, column_359 int, column_360 int, column_361 int, column_362 int, column_363 int, column_364 int, column_365 int, column_366 int, column_367 int, column_368 int, column_369 int, column_370 int, column_371 int, column_372 int, column_373 int, column_374 int, column_375 int, column_376 int, column_377 int, column_378 int, column_379 int, column_380 int, column_381 int, column_382 int, column_383 int, column_384 int, column_385 int, column_386 int, column_387 int, column_388 int, column_389 int, column_390 int, column_391 int, column_392 int, column_393 int, column_394 int, column_395 int, column_396 int, column_397 int, column_398 int, column_399 int, column_400 int, column_401 int, column_402 int, column_403 int, column_404 int, column_405 int, column_406 int, column_407 int, column_408 int, column_409 int, column_410 int, column_411 int, column_412 int, column_413 int, column_414 int, column_415 int, column_416 int, column_417 int, column_418 int, column_419 int, column_420 int, column_421 int, column_422 int, column_423 int, column_424 int, column_425 int, column_426 int, column_427 int, column_428 int, column_429 int, column_430 int, column_431 int, column_432 int, column_433 int, column_434 int, column_435 int, column_436 int, column_437 int, column_438 int, column_439 int, column_440 int, column_441 int, column_442 int, column_443 int, column_444 int, column_445 int, column_446 int, column_447 int, column_448 int, column_449 int, column_450 int, column_451 int, column_452 int, column_453 int, column_454 int, column_455 int, column_456 int, column_457 int, column_458 int, column_459 int, column_460 int, column_461 int, column_462 int, column_463 int, column_464 int, column_465 int, column_466 int, column_467 int, column_468 int, column_469 int, column_470 int, column_471 int, column_472 int, column_473 int, column_474 int, column_475 int, column_476 int, column_477 int, column_478 int, column_479 int, column_480 int, column_481 int, column_482 int, column_483 int, column_484 int, column_485 int, column_486 int, column_487 int, column_488 int, column_489 int, column_490 int, column_491 int, column_492 int, column_493 int, column_494 int, column_495 int, column_496 int, column_497 int, column_498 int, column_499 int, column_500 int, column_501 int, column_502 int, column_503 int, column_504 int, column_505 int, column_506 int, column_507 int, column_508 int, column_509 int, column_510 int, column_511 int, column_512 int, column_513 int, column_514 int, column_515 int, column_516 int, column_517 int, column_518 int, column_519 int, column_520 int, column_521 int, column_522 int, column_523 int, column_524 int, column_525 int, column_526 int, column_527 int, column_528 int, column_529 int, column_530 int, column_531 int, column_532 int, column_533 int, column_534 int, column_535 int, column_536 int, column_537 int, column_538 int, column_539 int, column_540 int, column_541 int, column_542 int, column_543 int, column_544 int, column_545 int, column_546 int, column_547 int, column_548 int, column_549 int, column_550 int, column_551 int, column_552 int, column_553 int, column_554 int, column_555 int, column_556 int, column_557 int, column_558 int, column_559 int, column_560 int, column_561 int, column_562 int, column_563 int, column_564 int, column_565 int, column_566 int, column_567 int, column_568 int, column_569 int, column_570 int, column_571 int, column_572 int, column_573 int, column_574 int, column_575 int, column_576 int, column_577 int, column_578 int, column_579 int, column_580 int, column_581 int, column_582 int, column_583 int, column_584 int, column_585 int, column_586 int, column_587 int, column_588 int, column_589 int, column_590 int, column_591 int, column_592 int, column_593 int, column_594 int, column_595 int, column_596 int, column_597 int, column_598 int, column_599 int, column_600 int, column_601 int, column_602 int, column_603 int, column_604 int, column_605 int, column_606 int, column_607 int, column_608 int, column_609 int, column_610 int, column_611 int, column_612 int, column_613 int, column_614 int, column_615 int, column_616 int, column_617 int, column_618 int, column_619 int, column_620 int, column_621 int, column_622 int, column_623 int, column_624 int, column_625 int, column_626 int, column_627 int, column_628 int, column_629 int, column_630 int, column_631 int, column_632 int, column_633 int, column_634 int, column_635 int, column_636 int, column_637 int, column_638 int, column_639 int, column_640 int, column_641 int, column_642 int, column_643 int, column_644 int, column_645 int, column_646 int, column_647 int, column_648 int, column_649 int, column_650 int, column_651 int, column_652 int, column_653 int, column_654 int, column_655 int, column_656 int, column_657 int, column_658 int, column_659 int, column_660 int, column_661 int, column_662 int, column_663 int, column_664 int, column_665 int, column_666 int, column_667 int, column_668 int, column_669 int, column_670 int, column_671 int, column_672 int, column_673 int, column_674 int, column_675 int, column_676 int, column_677 int, column_678 int, column_679 int, column_680 int, column_681 int, column_682 int, column_683 int, column_684 int, column_685 int, column_686 int, column_687 int, column_688 int, column_689 int, column_690 int, column_691 int, column_692 int, column_693 int, column_694 int, column_695 int, column_696 int, column_697 int, column_698 int, column_699 int, column_700 int, column_701 int, column_702 int, column_703 int, column_704 int, column_705 int, column_706 int, column_707 int, column_708 int, column_709 int, column_710 int, column_711 int, column_712 int, column_713 int, column_714 int, column_715 int, column_716 int, column_717 int, column_718 int, column_719 int, column_720 int, column_721 int, column_722 int, column_723 int, column_724 int, column_725 int, column_726 int, column_727 int, column_728 int, column_729 int, column_730 int, column_731 int, column_732 int, column_733 int, column_734 int, column_735 int, column_736 int, column_737 int, column_738 int, column_739 int, column_740 int, column_741 int, column_742 int, column_743 int, column_744 int, column_745 int, column_746 int, column_747 int, column_748 int, column_749 int, column_750 int, column_751 int, column_752 int, column_753 int, column_754 int, column_755 int, column_756 int, column_757 int, column_758 int, column_759 int, column_760 int, column_761 int, column_762 int, column_763 int, column_764 int, column_765 int, column_766 int, column_767 int, column_768 int, column_769 int, column_770 int, column_771 int, column_772 int, column_773 int, column_774 int, column_775 int, column_776 int, column_777 int, column_778 int, column_779 int, column_780 int, column_781 int, column_782 int, column_783 int, column_784 int, column_785 int, column_786 int, column_787 int, column_788 int, column_789 int, column_790 int, column_791 int, column_792 int, column_793 int, column_794 int, column_795 int, column_796 int, column_797 int, column_798 int, column_799 int, column_800 int, column_801 int, column_802 int, column_803 int, column_804 int, column_805 int, column_806 int, column_807 int, column_808 int, column_809 int, column_810 int, column_811 int, column_812 int, column_813 int, column_814 int, column_815 int, column_816 int, column_817 int, column_818 int, column_819 int, column_820 int, column_821 int, column_822 int, column_823 int, column_824 int, column_825 int, column_826 int, column_827 int, column_828 int, column_829 int, column_830 int, column_831 int, column_832 int, column_833 int, column_834 int, column_835 int, column_836 int, column_837 int, column_838 int, column_839 int, column_840 int, column_841 int, column_842 int, column_843 int, column_844 int, column_845 int, column_846 int, column_847 int, column_848 int, column_849 int, column_850 int, column_851 int, column_852 int, column_853 int, column_854 int, column_855 int, column_856 int, column_857 int, column_858 int, column_859 int, column_860 int, column_861 int, column_862 int, column_863 int, column_864 int, column_865 int, column_866 int, column_867 int, column_868 int, column_869 int, column_870 int, column_871 int, column_872 int, column_873 int, column_874 int, column_875 int, column_876 int, column_877 int, column_878 int, column_879 int, column_880 int, column_881 int, column_882 int, column_883 int, column_884 int, column_885 int, column_886 int, column_887 int, column_888 int, column_889 int, column_890 int, column_891 int, column_892 int, column_893 int, column_894 int, column_895 int, column_896 int, column_897 int, column_898 int, column_899 int)";
    String ctasQuery = String.format("CREATE TABLE %s.%s %s", TEMP_SCHEMA, tableName, cols);
    test(ctasQuery);

    String relativePath =  JSON_SOURCE_FOLDER + "test900fields.csv";
    File location = createTempLocation();
    File newSourceFile = new File(location.toString(), "test900fields.csv");
    File oldSourceFile = FileUtils.getResourceAsFile(relativePath);
    Files.copy(oldSourceFile, newSourceFile);
    String storageLocation = "'@" + SOURCE + "/" + location.getName() + "'";
    String copyIntoQuery = String.format("COPY INTO %s.%s FROM %s FILE_FORMAT 'csv' (RECORD_DELIMITER '\n')", TEMP_SCHEMA, tableName, storageLocation);
    test(copyIntoQuery);

    String dropQuery = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, tableName);
    test(dropQuery);
  }

  @Test
  public void testCopyIntoStockIcebergTable() throws Exception {
    try (DmlQueryTestUtils.Table table = DmlQueryTestUtils.createStockIcebergTable(
      TEMP_SCHEMA_HADOOP, 2, 1, "test_copy_into_stock_iceberg")) {
      File location = createTempLocation();

      String fileName = "Integer.json";
      String relativePath = String.format("/store/json/copyintosource/%s", fileName);
      File newSourceFile = new File(location.toString(), fileName);
      File oldSourceFile = FileUtils.getResourceAsFile(relativePath);
      Files.copy(oldSourceFile, newSourceFile);

      String storageLocation = "\'@" + TEMP_SCHEMA_HADOOP + "/" + location.getName() + "\'";

      String copyIntoQuery = String.format("COPY INTO %s FROM %s regex \'%s\' (RECORD_DELIMITER '\n')", table.fqn, storageLocation, fileName);
      test(copyIntoQuery);

      new TestBuilder(allocator)
        .sqlQuery("SELECT * FROM %s", table.fqn)
        .unOrdered()
        .baselineColumns("id")
        .baselineValues(1)
        .baselineValues(2)
        .go();
    }
  }
}
