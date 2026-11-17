package com.ruoyi.warehouse.data;
import com.ruoyi.warehouse.support.WarehouseException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MasterWorkbookTest {
    List<Map<String,Object>> fields=List.of(Map.of("key","sku","type","text","label","编码","labelEn","SKU"),Map.of("key","rate","type","number"),Map.of("key","name","type","text"));
    @Test void csvPreservesBomMultilineQuotedCommasZerosAndDecimals(){var parsed=MasterWorkbook.read("data.csv","\ufeffsku,rate,name\r\n000123,0.123456789,\"A,\"\"quoted\"\"\r\nname\"\r\n".getBytes(StandardCharsets.UTF_8),fields);var row=(Map<?,?>)parsed.rows().get(0).get("data");assertEquals("000123",row.get("sku"));assertEquals("0.123456789",row.get("rate"));assertEquals("A,\"quoted\"\r\nname",row.get("name"));}
    @Test void malformedOrDuplicateHeadersAndQuotesFail(){for(String input:List.of("sku,SKU\nx,y","sku\n\"unclosed","sku\n\"closed\"junk","unknown\nvalue"))assertThrows(WarehouseException.class,()->MasterWorkbook.read("a.csv",input.getBytes(StandardCharsets.UTF_8),fields));}
    @Test void ambiguousLabelsCannotSilentlyWriteAnotherField(){var schema=List.<Map<String,Object>>of(Map.of("key","a","type","text","label","相同标签"),Map.of("key","b","type","text","label","相同标签"));assertThrows(WarehouseException.class,()->MasterWorkbook.read("a.csv","相同标签\nwrong".getBytes(StandardCharsets.UTF_8),schema));assertEquals("value",((Map<?,?>)MasterWorkbook.read("a.csv","a\nvalue".getBytes(StandardCharsets.UTF_8),schema).rows().get(0).get("data")).get("a"));}
    @Test void xlsxExportsLiteralFormulasAndFullDecimalTextThenRoundTrips()throws Exception{var original=Map.<String,Object>of("sku","00012345678901234567890","rate","0.123456789","name","=SUM(1,2)");byte[] bytes=MasterWorkbook.write(fields,List.of(original),false,Map.of("Mode","create"));try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))){assertEquals(CellType.STRING,workbook.getSheetAt(0).getRow(1).getCell(2).getCellType());}assertEquals(original,MasterWorkbook.read("a.xlsx",bytes,fields).rows().get(0).get("data"));}
    @Test void xlsxFormulaAndNumericIdentifiersAreRejected()throws Exception{for(boolean formula:List.of(true,false)){try(var workbook=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()){var sheet=workbook.createSheet();sheet.createRow(0).createCell(0).setCellValue("sku");var cell=sheet.createRow(1).createCell(0);if(formula)cell.setCellFormula("1+1");else cell.setCellValue(123);workbook.write(bytes);assertThrows(WarehouseException.class,()->MasterWorkbook.read("a.xlsx",bytes.toByteArray(),fields));}}}
    @Test void limitsAndInvalidUtf8AreExplicit(){assertThrows(WarehouseException.class,()->MasterWorkbook.read("a.csv",new byte[]{(byte)0xff},fields));assertThrows(WarehouseException.class,()->MasterWorkbook.read("a.csv",new byte[MasterWorkbook.MAX_BYTES+1],fields));assertThrows(WarehouseException.class,()->MasterWorkbook.read("a.csv",("sku\n"+"x\n".repeat(501)).getBytes(StandardCharsets.UTF_8),fields));}
    @Test void clearMarkerHasAnUnambiguousLiteralEscape(){for(String value:List.of("__CLEAR__","\\__CLEAR__","\\\\__CLEAR__","__CLEAR__more")){var bytes=MasterWorkbook.write(fields,List.of(Map.of("sku",value)),false,Map.of());assertEquals(value,((Map<?,?>)MasterWorkbook.read("a.xlsx",bytes,fields).rows().get(0).get("data")).get("sku"));}assertEquals("",((Map<?,?>)MasterWorkbook.read("a.csv","sku\n__CLEAR__".getBytes(StandardCharsets.UTF_8),fields).rows().get(0).get("data")).get("sku"));}
}
