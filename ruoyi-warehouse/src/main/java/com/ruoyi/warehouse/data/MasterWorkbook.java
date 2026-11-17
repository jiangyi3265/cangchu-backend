package com.ruoyi.warehouse.data;

import com.ruoyi.warehouse.support.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

/** Bounded value-only workbooks; no formula evaluation, macros or external links. */
public final class MasterWorkbook {
    public static final int MAX_ROWS=500,MAX_BYTES=2*1024*1024;
    public record Table(List<String> headers,List<Map<String,Object>> rows){}
    public static Table read(String filename,byte[] bytes,List<Map<String,Object>> fields){
        if(bytes.length==0||bytes.length>MAX_BYTES)throw WarehouseException.invalid("File must be nonempty and at most 2 MiB / 文件不能为空且不能超过 2 MiB");
        List<List<String>> raw;
        try{String name=filename.toLowerCase(Locale.ROOT);if(name.endsWith(".csv"))raw=csv(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());else if(name.endsWith(".xlsx"))raw=xlsx(bytes,fields);else throw WarehouseException.invalid("Use UTF-8 CSV or XLSX / 请使用 UTF-8 CSV 或 XLSX");}
        catch(WarehouseException e){throw e;}catch(Exception e){throw WarehouseException.invalid("Cannot read this value-only workbook / 无法读取此表格，请使用不含公式的普通表格");}
        if(raw.isEmpty()||raw.size()<2)throw WarehouseException.invalid("Header and data rows are required / 请提供表头和数据行");
        var aliases=new HashMap<String,Set<String>>();var keys=new HashSet<String>();keys.addAll(List.of("id","_version"));for(var f:fields){keys.add(text(f,"key"));for(String alias:List.of("label","labelEn")){String text=WarehouseJson.text(f,alias);if(!text.isEmpty())aliases.computeIfAbsent(text,k->new HashSet<>()).add(text(f,"key"));}}
        var headers=new ArrayList<String>();for(String name:raw.get(0)){String header=name.strip();var candidates=aliases.getOrDefault(header,Set.of());if(!keys.contains(header)&&candidates.size()>1)throw WarehouseException.invalid("Ambiguous column label; use its field key: "+name+" / 列名称有歧义，请使用模板字段名");String key=keys.contains(header)?header:candidates.size()==1?candidates.iterator().next():null;if(key==null)throw WarehouseException.invalid("Unknown or restricted column: "+name+" / 未知或无权限的列");if(headers.contains(key))throw WarehouseException.invalid("Duplicate column: "+key+" / 表头重复");headers.add(key);}
        if(headers.isEmpty()||headers.size()>150)throw WarehouseException.invalid("Invalid header width / 表头列数无效");
        var result=new ArrayList<Map<String,Object>>();for(int i=1;i<raw.size();i++){var values=raw.get(i);if(values.stream().allMatch(String::isBlank))continue;if(values.size()>headers.size())throw WarehouseException.invalid("Row "+(i+1)+" has more cells than headers / 数据列超过表头");var data=new LinkedHashMap<String,Object>();for(int j=0;j<values.size();j++){String value=values.get(j);if(value.isEmpty())continue;data.put(headers.get(j),"__CLEAR__".equals(value)?"":value.startsWith("\\")&&clearLiteral(value)?value.substring(1):value);}result.add(Map.of("row",i+1,"data",data));if(result.size()>MAX_ROWS)throw WarehouseException.invalid("At most 500 data rows per batch / 每批最多 500 行，请分批导入");}
        if(result.isEmpty())throw WarehouseException.invalid("No data rows / 没有可导入数据");return new Table(headers,result);
    }
    static List<List<String>> csv(String value){
        if(value.startsWith("\ufeff"))value=value.substring(1);var result=new ArrayList<List<String>>();var row=new ArrayList<String>();var cell=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(quoted){if(c=='"'){if(i+1<value.length()&&value.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}else cell.append(c);}
            else if(c=='"'){if(cell.length()!=0||closed)throw WarehouseException.invalid("Invalid CSV quote / CSV 引号不完整");quoted=true;}
            else if(c==','||c=='\r'||c=='\n'){row.add(cell.toString());cell.setLength(0);closed=false;if(row.size()>150)throw WarehouseException.invalid("Too many columns");if(c!=','){result.add(row);row=new ArrayList<>();if(c=='\r'&&i+1<value.length()&&value.charAt(i+1)=='\n')i++;if(result.size()>MAX_ROWS+1)throw WarehouseException.invalid("At most 500 data rows per batch / 每批最多 500 行");}}
            else{if(closed)throw WarehouseException.invalid("Unexpected text after a CSV quote / CSV 引号后有多余内容");cell.append(c);}if(cell.length()>32767)throw WarehouseException.invalid("Cell exceeds text limit / 单元格内容过长");}
        if(quoted)throw WarehouseException.invalid("Unclosed CSV quote / CSV 引号未闭合");if(cell.length()>0||closed||!row.isEmpty()){row.add(cell.toString());result.add(row);}return result;
    }
    private static List<List<String>> xlsx(byte[] bytes,List<Map<String,Object>> fields)throws IOException{
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){long inflated=0;int entries=0;byte[] buffer=new byte[8192];for(ZipEntry e;(e=zip.getNextEntry())!=null;){String name=e.getName().toLowerCase(Locale.ROOT);if(++entries>2000||name.contains("vbaproject")||name.contains("externallinks/")||name.contains("embeddings/"))throw WarehouseException.invalid("Unsupported workbook content / 表格含不支持的宏或外部内容");for(int n;(n=zip.read(buffer))!=-1;){inflated+=n;if(inflated>32L*1024*1024)throw WarehouseException.invalid("Expanded workbook exceeds capacity / 解压后表格过大");}}}
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))){if(workbook.getNumberOfSheets()==0)throw WarehouseException.invalid("No worksheet");var sheet=workbook.getSheetAt(0);if(sheet.getLastRowNum()>MAX_ROWS)throw WarehouseException.invalid("At most 500 data rows per batch / 每批最多 500 行");var result=new ArrayList<List<String>>();
            var types=new HashMap<String,String>();for(var field:fields)for(String key:List.of("key","label","labelEn"))types.put(text(field,key),text(field,"type"));types.put("_version","number");
            for(int i=0;i<=sheet.getLastRowNum();i++){Row row=sheet.getRow(i);var values=new ArrayList<String>();if(row!=null){if(row.getLastCellNum()>150)throw WarehouseException.invalid("Too many columns");for(int j=0;j<row.getLastCellNum();j++){Cell cell=row.getCell(j);String value="";if(cell!=null){switch(cell.getCellType()){
                case STRING -> value=cell.getStringCellValue();case BLANK -> {} case BOOLEAN -> value=Boolean.toString(cell.getBooleanCellValue());
                case NUMERIC -> {String header=i==0||j>=result.get(0).size()?"":result.get(0).get(j);if(!"number".equals(types.get(header)))throw WarehouseException.invalid("Row "+(i+1)+", column "+(j+1)+": use a text cell for codes, dates and names / 编码、日期和名称请设为文本，避免丢失前导零");value=((XSSFCell)cell).getRawValue();}
                default -> throw WarehouseException.invalid("Row "+(i+1)+", column "+(j+1)+": formulas and error cells are not imported / 请将公式或错误单元格转换为明确值");
            }}values.add(value);}}result.add(values);}return result;
        }
    }
    public static byte[] write(List<Map<String,Object>> fields,List<Map<String,Object>> rows,boolean updating,Map<String,Object> meta){
        try(var workbook=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("Data");var keys=new ArrayList<String>();if(updating){keys.add("id");keys.add("_version");}for(var f:fields)keys.add(text(f,"key"));
            var style=workbook.createCellStyle();style.setDataFormat(workbook.createDataFormat().getFormat("@"));var bold=workbook.createCellStyle();var font=workbook.createFont();font.setBold(true);bold.setFont(font);bold.setDataFormat(style.getDataFormat());
            var header=sheet.createRow(0);for(int c=0;c<keys.size();c++){sheet.setDefaultColumnStyle(c,style);sheet.setColumnWidth(c,24*256);var cell=header.createCell(c,CellType.STRING);cell.setCellValue(keys.get(c));cell.setCellStyle(bold);}
            for(int i=0;i<rows.size();i++){var row=sheet.createRow(i+1);for(int c=0;c<keys.size();c++){Object value=rows.get(i).get(keys.get(c));String text=value==null?"":value instanceof java.math.BigDecimal n?n.toPlainString():value.toString();if(clearLiteral(text))text="\\"+text;var cell=row.createCell(c,CellType.STRING);cell.setCellValue(text);cell.setCellStyle(style);}}
            sheet.createFreezePane(0,1);var help=workbook.createSheet("Instructions");help.setColumnWidth(0,35*256);help.setColumnWidth(1,100*256);int n=0;
            for(var entry:meta.entrySet()){var row=help.createRow(n++);row.createCell(0,CellType.STRING).setCellValue(entry.getKey());row.createCell(1,CellType.STRING).setCellValue(String.valueOf(entry.getValue()));}
            for(var field:fields){var row=help.createRow(n++);row.createCell(0,CellType.STRING).setCellValue(text(field,"key"));row.createCell(1,CellType.STRING).setCellValue(text(field,"label")+" / "+text(field,"labelEn")+"; "+text(field,"type")+(Boolean.TRUE.equals(field.get("required"))?"; required / 必填":"")+(field.containsKey("source")?"; "+field.get("source")+" ID or unique name / 编号或范围内唯一名称":"")+(field.containsKey("options")?"; "+field.get("options"):""));}
            workbook.write(bytes);return bytes.toByteArray();
        }catch(IOException e){throw new IllegalStateException("Workbook generation failed",e);}
    }
    private static String text(Map<String,Object> map,String key){return WarehouseJson.text(map,key);}
    private static boolean clearLiteral(String text){int index=0;while(index<text.length()&&text.charAt(index)=='\\')index++;return text.substring(index).startsWith("__CLEAR__");}
}
