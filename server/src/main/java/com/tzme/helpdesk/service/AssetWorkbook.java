package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.domain.AssetCategory;
import com.tzme.helpdesk.dto.AssetCreateReq;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class AssetWorkbook {
    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DATA_SHEET = "资产导入";
    private static final String HELP_SHEET = "填写说明";
    private static final String EXAMPLE_SHEET = "填写示例";
    private static final int MAX_ROWS = 500;
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_UNCOMPRESSED_BYTES = 16 * 1024 * 1024;
    private static final List<String> STATUSES = List.of("待入库", "闲置", "在用", "维修中", "待报废", "已报废");
    private static final DateTimeFormatter SLASH_DATE = DateTimeFormatter.ofPattern("uuuu/M/d")
            .withResolverStyle(ResolverStyle.STRICT);

    private final AssetCategoryService categories;
    private final Validator validator;

    private enum Column {
        ERP_NO("ERP资产编号", "erpNo", 23), NAME("设备名称", "name", 28), CATEGORY("资产分类", "category", 20),
        MODEL("型号", "model", 25), SN("SN 序列号", "sn", 22), LOCATION("存放位置", "location", 26),
        BUY_DATE("购置日期", "buyDate", 16), WARRANTY("维保方式", "warranty", 18),
        WARRANTY_END("维保到期", "warrantyEnd", 16), STATUS("设备状态", "status", 16),
        REMARK("备注", "remark", 35), ICON("卡片图标", "icon", 18),
        USER_NAME("使用人", "userName", 20);

        final String label;
        final String property;
        final int width;

        Column(String label, String property, int width) {
            this.label = label;
            this.property = property;
            this.width = width;
        }
    }

    public record AssetRow(int number, AssetCreateReq request, String userName) { }

    public byte[] template() {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(DATA_SHEET);
            Sheet help = workbook.createSheet(HELP_SHEET);
            Sheet example = workbook.createSheet(EXAMPLE_SHEET);
            List<String> categoryNames = categories.list().stream().map(AssetCategory::getName).toList();
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            CellStyle heading = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            heading.setFont(font);
            heading.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            writeHeader(sheet, heading, textStyle, dateStyle);
            writeHeader(example, heading, textStyle, dateStyle);
            writeExample(example, categoryNames, dateStyle);
            help.setColumnWidth(0, 100 * 256);
            help.setColumnWidth(1, 24 * 256);
            help.setColumnWidth(2, 18 * 256);
            List<String> instructions = List.of(
                    "填写说明",
                    "参照「填写示例」页，从「资产导入」第 2 行开始填写。每行一台设备，请保留表头。",
                    "设备名称、资产分类必填。资产编号由系统生成。",
                    "ERP资产编号选填，填写时在台账和本表内均须唯一。",
                    "资产分类使用台账已有分类；资产分类和设备状态都可以从下拉选项里选择。",
                    "每次最多导入 500 台，文件上限 5 MB。",
                    "使用人选填，请填写通讯录中唯一的在职人员姓名。填写后，状态留空会设为在用。",
                    "已填写使用人时，设备状态可留空或填写在用。",
                    "使用人和状态均留空时默认为待入库，有存放位置时会设为闲置。",
                    "借出中的设备请通过资产借用流程登记。",
                    "购置日期和维保到期请使用日期单元格，或填写 YYYY-MM-DD。",
                    "维保到期应等于或晚于购置日期。维保方式可留空，例如厂商保修、自保、已过保。",
                    "ERP资产编号与 SN 已设为文本格式。粘贴时请保留文本，以保留前导零。",
                    "卡片图标留空或填 auto 会自动匹配，也可填 laptop、desktop、monitor、projector、",
                    "keyboard、mouse、headset、printer、server、network、phone、tablet 或 generic。"
            );
            for (int i = 0; i < instructions.size(); i++) help.createRow(i).createCell(0).setCellValue(instructions.get(i));
            help.getRow(0).createCell(1).setCellValue("可用分类");
            help.getRow(0).createCell(2).setCellValue("可用状态");
            addChoices(workbook, sheet, help, Column.CATEGORY, 'B', "AssetCategories", categoryNames);
            addChoices(workbook, sheet, help, Column.STATUS, 'C', "AssetStatuses", STATUSES);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成资产导入模板失败", e);
        }
    }

    private void writeHeader(Sheet sheet, CellStyle heading, CellStyle textStyle, CellStyle dateStyle) {
        Row header = sheet.createRow(0);
        for (Column column : Column.values()) {
            int index = column.ordinal();
            Cell cell = header.createCell(index);
            cell.setCellValue(column.label);
            cell.setCellStyle(heading);
            sheet.setColumnWidth(index, column.width * 256);
            sheet.setDefaultColumnStyle(index, column == Column.BUY_DATE || column == Column.WARRANTY_END ? dateStyle : textStyle);
        }
        sheet.createFreezePane(3, 1);
    }

    private void writeExample(Sheet example, List<String> categoryNames, CellStyle dateStyle) {
        Row row = example.createRow(1);
        row.createCell(Column.ERP_NO.ordinal()).setCellValue("ERP-DEMO-0001");
        row.createCell(Column.NAME.ordinal()).setCellValue("办公笔记本");
        if (!categoryNames.isEmpty()) {
            String category = categoryNames.stream().filter(name -> name.startsWith("终端")).findFirst().orElse(categoryNames.get(0));
            row.createCell(Column.CATEGORY.ordinal()).setCellValue(category);
        }
        row.createCell(Column.MODEL.ordinal()).setCellValue("ThinkPad T14");
        row.createCell(Column.SN.ordinal()).setCellValue("00012345");
        row.createCell(Column.LOCATION.ordinal()).setCellValue("A座三楼备件库");
        Cell buyDate = row.createCell(Column.BUY_DATE.ordinal());
        buyDate.setCellValue(LocalDate.of(2026, 1, 10));
        buyDate.setCellStyle(dateStyle);
        row.createCell(Column.WARRANTY.ordinal()).setCellValue("厂商保修");
        Cell warrantyEnd = row.createCell(Column.WARRANTY_END.ordinal());
        warrantyEnd.setCellValue(LocalDate.of(2029, 1, 10));
        warrantyEnd.setCellStyle(dateStyle);
        row.createCell(Column.STATUS.ordinal()).setCellValue("闲置");
        row.createCell(Column.REMARK.ordinal()).setCellValue("备用设备（填写示例）");
        row.createCell(Column.ICON.ordinal()).setCellValue("laptop");
    }

    private void addChoices(Workbook workbook, Sheet sheet, Sheet help, Column column,
                            char helpColumn, String range, List<String> options) {
        if (options.isEmpty()) return;
        for (int i = 0; i < options.size(); i++) {
            Row row = help.getRow(i + 1);
            if (row == null) row = help.createRow(i + 1);
            row.createCell(helpColumn - 'A').setCellValue(options.get(i));
        }
        Name name = workbook.createName();
        name.setNameName(range);
        name.setRefersToFormula("'" + HELP_SHEET + "'!$" + helpColumn + "$2:$" + helpColumn + "$" + (options.size() + 1));
        var helper = sheet.getDataValidationHelper();
        int index = column.ordinal();
        var validation = helper.createValidation(helper.createFormulaListConstraint(range),
                new CellRangeAddressList(1, MAX_ROWS, index, index));
        validation.setShowErrorBox(true);
        validation.createErrorBox("填写有误", "请从下拉选项中选择。");
        sheet.addValidationData(validation);
    }

    public List<AssetRow> read(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("请选择要导入的 Excel 文件");
        if (file.getSize() > MAX_BYTES) throw new BizException("Excel 文件不能超过 5 MB");
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw new BizException("请上传 .xlsx 格式的 Excel 文件");
        DataFormatter format = new DataFormatter(Locale.ROOT);
        try {
            byte[] bytes = file.getBytes();
            checkArchiveSize(bytes);
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                Sheet sheet = workbook.getSheet(DATA_SHEET);
                if (sheet == null || sheet.getRow(0) == null) throw invalidHeaders();
                Row header = sheet.getRow(0);
                int columnCount = Column.values().length;
                // 旧版模板没有最后一列，仍可用于导入未分配使用人的资产。
                if (format.formatCellValue(header.getCell(Column.USER_NAME.ordinal())).isBlank()) columnCount--;
                for (Column column : Column.values()) {
                    if (column.ordinal() >= columnCount) continue;
                    String label = format.formatCellValue(header.getCell(column.ordinal())).strip();
                    if (column == Column.ERP_NO && "资产编号".equals(label)) continue;
                    if (!column.label.equals(label)) throw invalidHeaders();
                }
                if (hasExtraValues(header, columnCount)) throw invalidHeaders();
                List<AssetRow> rows = new ArrayList<>();
                for (Row row : sheet) {
                    if (row.getRowNum() == 0 || !hasValues(row)) continue;
                    if (rows.size() == MAX_ROWS) throw new BizException("一次最多导入 500 台资产，请分批上传");
                    int number = row.getRowNum() + 1;
                    try {
                        if (hasExtraValues(row, columnCount)) throw new BizException("有数据填写在模板列之外");
                        rows.add(parseRow(row, format, number));
                    } catch (BizException e) {
                        throw new BizException("第 " + number + " 行：" + e.getMessage());
                    }
                }
                if (rows.isEmpty()) throw new BizException("请在模板第 2 行起填写要导入的资产");
                return rows;
            }
        } catch (IOException | IllegalArgumentException | POIXMLException e) {
            throw new BizException("无法读取 Excel 文件，请使用模板另存为 .xlsx 后重试");
        }
    }

    private void checkArchiveSize(byte[] bytes) throws IOException {
        int total = 0;
        int entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            while (zip.getNextEntry() != null) {
                if (++entries > 200) throw new BizException("Excel 内容过多，请使用模板分批上传");
                for (int count; (count = zip.read(buffer)) != -1; ) {
                    total += count;
                    if (total > MAX_UNCOMPRESSED_BYTES) throw new BizException("Excel 内容过多，请使用模板分批上传");
                }
            }
        }
    }

    private BizException invalidHeaders() {
        return new BizException("资产导入表头与模板不一致，请下载模板后将数据填入对应列");
    }

    private boolean hasValue(Cell cell) {
        return cell.getCellType() != CellType.BLANK
                && (cell.getCellType() != CellType.STRING || !cell.getStringCellValue().isBlank());
    }

    private boolean hasValues(Row row) {
        for (Cell cell : row) if (hasValue(cell)) return true;
        return false;
    }

    private boolean hasExtraValues(Row row, int columnCount) {
        for (Cell cell : row) if (cell.getColumnIndex() >= columnCount && hasValue(cell)) return true;
        return false;
    }

    private AssetRow parseRow(Row row, DataFormatter format, int number) {
        var req = new AssetCreateReq();
        req.setErpNo(text(row, Column.ERP_NO, format));
        req.setName(text(row, Column.NAME, format));
        req.setCategory(text(row, Column.CATEGORY, format));
        req.setModel(text(row, Column.MODEL, format));
        req.setSn(text(row, Column.SN, format));
        req.setLocation(text(row, Column.LOCATION, format));
        req.setBuyDate(date(row, Column.BUY_DATE, format));
        req.setWarranty(text(row, Column.WARRANTY, format));
        req.setWarrantyEnd(date(row, Column.WARRANTY_END, format));
        String userName = text(row, Column.USER_NAME, format);
        if (userName != null && userName.length() > 64) throw new BizException("使用人姓名最多填写64字");
        String status = text(row, Column.STATUS, format);
        if (status != null) {
            if (!STATUSES.contains(status)) throw new BizException("请选择模板中的设备状态，借用状态通过资产申请更新");
            req.setStatus(status);
        } else if (userName != null) {
            req.setStatus("在用");
        }
        req.setRemark(text(row, Column.REMARK, format));
        req.setIcon(text(row, Column.ICON, format));
        var errors = validator.validate(req);
        for (Column column : Column.values()) {
            var error = errors.stream().filter(item -> item.getPropertyPath().toString().equals(column.property)).findFirst();
            if (error.isPresent()) throw new BizException(column.label + "：" + error.get().getMessage());
        }
        return new AssetRow(number, req, userName);
    }

    private String text(Row row, Column column, DataFormatter format) {
        Cell cell = row.getCell(column.ordinal());
        if (cell == null || !hasValue(cell)) return null;
        if (cell.getCellType() != CellType.STRING && cell.getCellType() != CellType.NUMERIC)
            throw new BizException(column.label + "请填写文本或数值，公式请先粘贴为值");
        String value = format.formatCellValue(cell).strip();
        return value.isEmpty() ? null : value;
    }

    private LocalDate date(Row row, Column column, DataFormatter format) {
        String value = text(row, column, format);
        if (value == null) return null;
        Cell cell = row.getCell(column.ordinal());
        try {
            LocalDate date = cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate()
                    : LocalDate.parse(value, value.contains("/") ? SLASH_DATE : DateTimeFormatter.ISO_LOCAL_DATE);
            if (date.getYear() >= 1000 && date.getYear() <= 9999) return date;
        } catch (DateTimeParseException ignored) {
            // Return the column-specific message below.
        }
        throw new BizException(column.label + "请填写有效日期，格式为 YYYY-MM-DD");
    }
}
