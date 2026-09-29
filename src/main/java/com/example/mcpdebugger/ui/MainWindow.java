package com.example.mcpdebugger.ui;

import com.example.mcpdebugger.model.*;
import com.example.mcpdebugger.persistence.ConfigRepository;
import com.example.mcpdebugger.protocol.McpClient;
import com.example.mcpdebugger.transport.HttpTransport;
import com.example.mcpdebugger.transport.McpTransport;
import com.example.mcpdebugger.transport.SseTransport;
import com.example.mcpdebugger.transport.StdioTransport;
import com.google.gson.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.List;
import javax.swing.*;

public class MainWindow extends JFrame {
    private final ConfigRepository repo;
    private final DefaultListModel<ServerConfig> servers = new DefaultListModel<ServerConfig>();
    private final JList<ServerConfig> serverList = new JList<ServerConfig>(servers);
    private final JTextArea log = new JTextArea();
    private final JComboBox<ToolDefinition> tools = new JComboBox<ToolDefinition>();
    private final JComboBox<ResourceDefinition> resources = new JComboBox<ResourceDefinition>();
    private final JComboBox<PromptDefinition> prompts = new JComboBox<PromptDefinition>();
    private final JTextArea promptArguments = new JTextArea();
    private final JPanel promptForm = new JPanel(new GridBagLayout());
    private final Map<String, JTextField> promptFields = new LinkedHashMap<String, JTextField>();
    private final JTextArea args = new JTextArea("{}", 8, 50);
    private final JTextArea toolResult = new JTextArea();
    private final DefaultListModel<String> callHistory = new DefaultListModel<String>();
    private final JList<String> callHistoryList = new JList<String>(callHistory);
    private final List<String> callHistoryArgs = new ArrayList<String>();
    private SwingWorker<?, ?> activeToolRequest;
    private final JTextArea toolSchema = new JTextArea();
    private final JPanel dynamicForm = new JPanel(new GridBagLayout());
    private final Map<String, JComponent> dynamicFields = new LinkedHashMap<String, JComponent>();
    private final Map<String, JsonObject> dynamicDefinitions =
            new LinkedHashMap<String, JsonObject>();
    private final Set<String> dynamicRequired = new HashSet<String>();
    private final JLabel connectionStatus = new JLabel("● 未连接");
    private McpTransport transport;
    private McpClient client;

    public MainWindow(ConfigRepository repo) {
        super("MCP Debugger Tools - Java");
        this.repo = repo;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent event) {
                        persist();
                        disconnect();
                    }
                });
        // The configuration form has several one-line controls; keep enough vertical
        // space so the native text fields do not get compressed and clip their glyphs.
        setSize(1100, 800);
        setLocationRelativeTo(null);
        buildMenu();
        build();
        load();
    }

    private void buildMenu() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("文件");
        JMenuItem importItem = new JMenuItem("导入 Server 配置");
        JMenuItem exportItem = new JMenuItem("导出 Server 配置");
        fileMenu.add(importItem);
        fileMenu.add(exportItem);
        menuBar.add(fileMenu);
        setJMenuBar(menuBar);
        importItem.addActionListener(e -> importServers());
        exportItem.addActionListener(e -> exportServers());
    }

    private void importServers() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        List<ServerConfig> imported = repo.load(chooser.getSelectedFile());
        servers.clear();
        for (ServerConfig server : imported) servers.addElement(server);
        if (!servers.isEmpty()) serverList.setSelectedIndex(0);
        persist();
        log("已导入 Server 配置: " + imported.size() + " 个");
    }

    private void exportServers() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("mcp-servers.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            repo.save(chooser.getSelectedFile(), Collections.list(servers.elements()));
            log("已导出 Server 配置");
        } catch (Exception e) {
            error("导出配置失败: " + e.getMessage());
        }
    }

    private void build() {
        JPanel left = new JPanel(new BorderLayout(5, 5));
        left.setBorder(BorderFactory.createTitledBorder("MCP Servers"));
        left.add(new JScrollPane(serverList));
        JPanel lb = new JPanel(new GridLayout(2, 2));
        JButton add = new JButton("新增"),
                del = new JButton("删除"),
                connect = new JButton("连接"),
                disconnect = new JButton("断开");
        lb.add(add);
        lb.add(del);
        lb.add(connect);
        lb.add(disconnect);
        left.add(lb, BorderLayout.SOUTH);
        connectionStatus.setForeground(new Color(180, 40, 40));
        connectionStatus.setFont(connectionStatus.getFont().deriveFont(Font.BOLD, 14f));
        add.setToolTipText("新增 Stdio Server");
        add.addActionListener(e -> addServer());
        del.addActionListener(e -> deleteServer());
        connect.addActionListener(e -> connect());
        disconnect.addActionListener(e -> disconnect());
        JPanel config = new JPanel(new GridLayout(0, 2, 5, 5));
        config.setBorder(BorderFactory.createTitledBorder("Server 配置"));
        JTextField name = new JTextField(),
                command = new JTextField(),
                params = new JTextField(),
                workdir = new JTextField(),
                url = new JTextField(),
                headers = new JTextField(),
                timeout = new JTextField(),
                retries = new JTextField(),
                retryDelay = new JTextField();
        name.setColumns(28);
        command.setColumns(28);
        params.setColumns(28);
        workdir.setColumns(28);
        url.setColumns(28);
        headers.setColumns(28);
        timeout.setColumns(28);
        retries.setColumns(28);
        retryDelay.setColumns(28);
        JTextField[] editableFields = {
            name, command, params, workdir, url, headers, timeout, retries, retryDelay
        };
        for (JTextField field : editableFields) {
            field.setEditable(true);
            field.setForeground(Color.BLACK);
            field.setBackground(Color.WHITE);
            field.setCaretColor(Color.BLACK);
            field.setFont(new Font("Dialog", Font.PLAIN, 14));
            // Give the native text renderer enough vertical room, especially on macOS.
            // The default one-line height clips the glyphs after setting the 14pt font.
            field.setPreferredSize(new Dimension(0, 28));
        }
        JComboBox<String> transportType = new JComboBox<>(new String[] {"stdio", "http", "sse"});
        transportType.setPreferredSize(new Dimension(0, 28));
        tools.setPreferredSize(new Dimension(420, 30));
        tools.setMaximumRowCount(12);
        config.add(new JLabel("名称"));
        config.add(name);
        config.add(new JLabel("传输类型"));
        config.add(transportType);
        config.add(new JLabel("命令"));
        config.add(command);
        config.add(new JLabel("参数（空格分隔）"));
        config.add(params);
        config.add(new JLabel("工作目录"));
        config.add(workdir);
        config.add(new JLabel("HTTP URL"));
        config.add(url);
        config.add(new JLabel("HTTP Headers JSON"));
        config.add(headers);
        config.add(new JLabel("请求超时（毫秒）"));
        config.add(timeout);
        config.add(new JLabel("最大重试次数"));
        config.add(retries);
        config.add(new JLabel("初始退避（毫秒）"));
        config.add(retryDelay);
        JButton save = new JButton("保存配置"), test = new JButton("测试连接");
        config.add(save);
        config.add(test);
        save.addActionListener(
                e -> {
                    ServerConfig s = serverList.getSelectedValue();
                    if (s != null) {
                        s.name = name.getText();
                        s.transport = (String) transportType.getSelectedItem();
                        s.command = command.getText();
                        s.args.clear();
                        if (!params.getText().trim().isEmpty())
                            s.args.addAll(Arrays.asList(params.getText().trim().split("\\s+")));
                        s.workingDirectory = workdir.getText();
                        s.url = url.getText();
                        try {
                            s.timeoutMillis = Integer.parseInt(timeout.getText().trim());
                            if (s.timeoutMillis <= 0) throw new NumberFormatException();
                        } catch (NumberFormatException ex) {
                            error("请求超时必须是正整数（毫秒）");
                            return;
                        }
                        try {
                            s.maxRetries = Integer.parseInt(retries.getText().trim());
                            s.retryDelayMillis = Long.parseLong(retryDelay.getText().trim());
                            if (s.maxRetries < 0 || s.retryDelayMillis < 0)
                                throw new NumberFormatException();
                        } catch (NumberFormatException ex) {
                            error("重试次数和退避时间必须是非负数字");
                            return;
                        }
                        try {
                            s.headers.clear();
                            if (!headers.getText().trim().isEmpty()) {
                                JsonObject headerObject =
                                        new JsonParser().parse(headers.getText()).getAsJsonObject();
                                for (Map.Entry<String, JsonElement> entry :
                                        headerObject.entrySet()) {
                                    if (!entry.getValue().isJsonPrimitive())
                                        throw new IllegalArgumentException("Header 值必须是字符串");
                                    s.headers.put(entry.getKey(), entry.getValue().getAsString());
                                }
                            }
                        } catch (Exception ex) {
                            error("HTTP Headers JSON 无效: " + ex.getMessage());
                            return;
                        }
                        serverList.repaint();
                        persist();
                    }
                });
        test.addActionListener(
                e -> {
                    save.doClick();
                    testConnection();
                });
        serverList.addListSelectionListener(
                e -> {
                    if (e.getValueIsAdjusting()) return;
                    ServerConfig s = serverList.getSelectedValue();
                    if (s != null) {
                        name.setText(s.name);
                        transportType.setSelectedItem(s.transport);
                        command.setText(s.command);
                        params.setText(String.join(" ", s.args));
                        workdir.setText(s.workingDirectory);
                        url.setText(s.url);
                        headers.setText(new Gson().toJson(s.headers));
                        timeout.setText(String.valueOf(s.timeoutMillis));
                        retries.setText(String.valueOf(s.maxRetries));
                        retryDelay.setText(String.valueOf(s.retryDelayMillis));
                    }
                });
        JPanel tool = new JPanel(new BorderLayout(5, 5));
        tool.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(connectionStatus);
        top.add(Box.createHorizontalStrut(18));
        top.add(new JLabel("Tool"));
        top.add(tools);
        JButton refresh = new JButton("刷新 Tools"),
                call = new JButton("调用 Tool"),
                cancel = new JButton("取消调用");
        top.add(refresh);
        top.add(call);
        top.add(cancel);
        tool.add(top, BorderLayout.NORTH);
        args.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        args.setEditable(true);
        args.setEnabled(true);
        args.setForeground(Color.BLACK);
        args.setBackground(Color.WHITE);
        args.setCaretColor(Color.BLACK);
        args.setLineWrap(true);
        args.setWrapStyleWord(true);
        args.setFont(new Font("Monospaced", Font.PLAIN, 14));
        toolResult.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        toolResult.setEditable(false);
        JPanel request = new JPanel(new BorderLayout(3, 3));
        request.add(new JLabel("请求参数 JSON"), BorderLayout.NORTH);
        request.add(new JScrollPane(args), BorderLayout.CENTER);
        toolSchema.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        toolSchema.setEditable(false);
        JTabbedPane requestTabs = new JTabbedPane();
        requestTabs.addTab("参数", request);
        requestTabs.addTab("Input Schema", new JScrollPane(toolSchema));
        JPanel formContainer = new JPanel(new BorderLayout(5, 5));
        formContainer.add(new JScrollPane(dynamicForm), BorderLayout.CENTER);
        JButton generateJson = new JButton("生成请求 JSON");
        formContainer.add(generateJson, BorderLayout.SOUTH);
        requestTabs.addTab("动态表单", formContainer);
        JPanel response = new JPanel(new BorderLayout(3, 3));
        response.add(new JLabel("Tool 响应 / 错误"), BorderLayout.NORTH);
        response.add(new JScrollPane(toolResult), BorderLayout.CENTER);
        callHistoryList.setPreferredSize(new Dimension(220, 100));
        callHistoryList.addListSelectionListener(
                e -> {
                    if (!e.getValueIsAdjusting()) {
                        int index = callHistoryList.getSelectedIndex();
                        if (index >= 0 && index < callHistoryArgs.size()) {
                            args.setText(callHistoryArgs.get(index));
                        }
                    }
                });
        JPanel history = new JPanel(new BorderLayout(3, 3));
        history.setBorder(BorderFactory.createTitledBorder("调用历史"));
        history.add(new JScrollPane(callHistoryList), BorderLayout.CENTER);
        JPanel responseWithHistory = new JPanel(new BorderLayout(5, 5));
        responseWithHistory.add(response, BorderLayout.CENTER);
        responseWithHistory.add(history, BorderLayout.EAST);
        JSplitPane requestResponse =
                new JSplitPane(JSplitPane.VERTICAL_SPLIT, requestTabs, responseWithHistory);
        requestResponse.setResizeWeight(0.5);
        request.setMinimumSize(new Dimension(500, 220));
        tool.add(requestResponse, BorderLayout.CENTER);
        args.setMinimumSize(new Dimension(400, 180));
        refresh.addActionListener(e -> listTools());
        call.addActionListener(e -> callTool());
        cancel.addActionListener(e -> cancelToolCall());
        generateJson.addActionListener(e -> generateJsonFromForm());
        tools.addActionListener(e -> showSelectedToolSchema());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Tools", tool);
        tabs.addTab("Resources", buildResourcesPanel());
        tabs.addTab("Prompts", buildPromptsPanel());
        tabs.addTab("JSON-RPC", buildJsonRpcPanel());
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("Logs", buildLogsPanel());
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, config, tabs);
        config.setMinimumSize(new Dimension(500, 310));
        tabs.setMinimumSize(new Dimension(500, 420));
        right.setDividerLocation(320);
        right.setResizeWeight(0.25);
        JSplitPane all = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        all.setDividerLocation(230);
        all.setResizeWeight(0.18);
        add(all);
    }

    private JPanel buildResourcesPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refresh = new JButton("刷新 Resources");
        JButton read = new JButton("读取 Resource");
        JButton export = new JButton("导出内容");
        top.add(resources);
        top.add(refresh);
        top.add(read);
        top.add(export);
        JTextArea result = new JTextArea();
        result.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(result), BorderLayout.CENTER);
        refresh.addActionListener(e -> listResources());
        read.addActionListener(e -> readResource(result));
        export.addActionListener(
                e -> {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setSelectedFile(new File("resource-content.txt"));
                    if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                        try (FileWriter writer = new FileWriter(chooser.getSelectedFile())) {
                            writer.write(result.getText());
                        } catch (IOException ex) {
                            error("导出 Resource 失败: " + ex.getMessage());
                        }
                    }
                });
        return panel;
    }

    private JPanel buildJsonRpcPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JTextField method = new JTextField("tools/list", 28);
        JButton send = new JButton("发送 JSON-RPC");
        top.add(new JLabel("Method"));
        top.add(method);
        top.add(send);
        JTextArea request = new JTextArea("{}", 8, 50);
        JTextArea response = new JTextArea();
        request.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        response.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        response.setEditable(false);
        JSplitPane split =
                new JSplitPane(
                        JSplitPane.VERTICAL_SPLIT,
                        new JScrollPane(request),
                        new JScrollPane(response));
        split.setResizeWeight(0.45);
        panel.add(top, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        send.addActionListener(
                e -> {
                    if (client == null) {
                        error("请先连接 Server");
                        return;
                    }
                    try {
                        JsonObject params =
                                new JsonParser().parse(request.getText()).getAsJsonObject();
                        new SwingWorker<JsonObject, Void>() {
                            protected JsonObject doInBackground() throws Exception {
                                return client.rawRequest(method.getText().trim(), params);
                            }

                            protected void done() {
                                try {
                                    response.setText(
                                            new GsonBuilder()
                                                    .setPrettyPrinting()
                                                    .create()
                                                    .toJson(get()));
                                } catch (Exception ex) {
                                    response.setText("请求失败：" + ex.getMessage());
                                }
                            }
                        }.execute();
                    } catch (Exception ex) {
                        error("Params 必须是 JSON Object: " + ex.getMessage());
                    }
                });
        return panel;
    }

    private JPanel buildLogsPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JTextField search = new JTextField(24);
        JButton find = new JButton("搜索");
        JButton clear = new JButton("清空");
        JButton export = new JButton("导出日志");
        JCheckBox autoScroll = new JCheckBox("自动滚动", true);
        toolbar.add(new JLabel("关键字"));
        toolbar.add(search);
        toolbar.add(find);
        toolbar.add(clear);
        toolbar.add(export);
        toolbar.add(autoScroll);
        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(new JScrollPane(log), BorderLayout.CENTER);
        find.addActionListener(
                e -> {
                    String keyword = search.getText().trim();
                    if (keyword.isEmpty()) return;
                    String content = log.getText();
                    int position = content.indexOf(keyword, Math.max(0, log.getCaretPosition()));
                    if (position < 0) position = content.indexOf(keyword);
                    if (position >= 0) {
                        log.requestFocus();
                        log.select(position, position + keyword.length());
                    }
                });
        clear.addActionListener(e -> log.setText(""));
        export.addActionListener(
                e -> {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setSelectedFile(new File("mcp-debug.log"));
                    if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                        try (FileWriter writer = new FileWriter(chooser.getSelectedFile())) {
                            writer.write(log.getText());
                        } catch (IOException ex) {
                            error("导出日志失败: " + ex.getMessage());
                        }
                    }
                });
        autoScroll.addActionListener(
                e -> log.putClientProperty("autoScroll", autoScroll.isSelected()));
        return panel;
    }

    private JPanel buildPromptsPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refresh = new JButton("刷新 Prompts");
        JButton get = new JButton("获取 Prompt");
        top.add(prompts);
        top.add(refresh);
        top.add(get);
        JTextArea result = new JTextArea("{}", 8, 50);
        promptArguments.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        promptArguments.setEditable(false);
        JPanel argumentPanel = new JPanel(new BorderLayout(3, 3));
        argumentPanel.add(new JLabel("参数定义"), BorderLayout.NORTH);
        argumentPanel.add(new JScrollPane(promptArguments), BorderLayout.CENTER);
        JPanel formPanel = new JPanel(new BorderLayout(3, 3));
        formPanel.add(new JScrollPane(promptForm), BorderLayout.CENTER);
        JButton generatePromptJson = new JButton("生成 Prompt 参数 JSON");
        formPanel.add(generatePromptJson, BorderLayout.SOUTH);
        JPanel inputPanel = new JPanel(new BorderLayout(3, 3));
        inputPanel.add(new JLabel("参数 JSON"), BorderLayout.NORTH);
        inputPanel.add(new JScrollPane(result), BorderLayout.CENTER);
        JSplitPane formSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, argumentPanel, formPanel);
        formSplit.setResizeWeight(0.35);
        JSplitPane inputSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, formSplit, inputPanel);
        inputSplit.setResizeWeight(0.55);
        JTextArea output = new JTextArea();
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JSplitPane split =
                new JSplitPane(JSplitPane.VERTICAL_SPLIT, inputSplit, new JScrollPane(output));
        split.setDividerLocation(130);
        panel.add(top, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        refresh.addActionListener(e -> listPrompts());
        get.addActionListener(e -> getPrompt(result, output));
        prompts.addActionListener(e -> showSelectedPromptArguments());
        generatePromptJson.addActionListener(e -> generatePromptJson(result));
        return panel;
    }

    private void showSelectedPromptArguments() {
        PromptDefinition prompt = (PromptDefinition) prompts.getSelectedItem();
        if (prompt == null || prompt.arguments == null || prompt.arguments.isEmpty()) {
            promptArguments.setText("该 Prompt 没有声明参数");
            promptForm.removeAll();
            promptFields.clear();
            return;
        }
        StringBuilder text = new StringBuilder();
        for (PromptArgument argument : prompt.arguments) {
            text.append(argument.name);
            if (Boolean.TRUE.equals(argument.required)) text.append(" *");
            if (argument.description != null) text.append(" - ").append(argument.description);
            text.append('\n');
        }
        promptArguments.setText(text.toString());
        promptArguments.setCaretPosition(0);
        promptForm.removeAll();
        promptFields.clear();
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(3, 5, 3, 5);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        int row = 0;
        for (PromptArgument argument : prompt.arguments) {
            constraints.gridx = 0;
            constraints.gridy = row;
            promptForm.add(
                    new JLabel(
                            argument.name + (Boolean.TRUE.equals(argument.required) ? " *" : "")),
                    constraints);
            JTextField field = new JTextField();
            field.setPreferredSize(new Dimension(260, 26));
            if (argument.description != null) field.setToolTipText(argument.description);
            constraints.gridx = 1;
            promptForm.add(field, constraints);
            promptFields.put(argument.name, field);
            row++;
        }
        promptForm.revalidate();
        promptForm.repaint();
    }

    private void generatePromptJson(JTextArea target) {
        JsonObject arguments = new JsonObject();
        for (Map.Entry<String, JTextField> entry : promptFields.entrySet()) {
            if (!entry.getValue().getText().trim().isEmpty())
                arguments.addProperty(entry.getKey(), entry.getValue().getText());
        }
        target.setText(new GsonBuilder().setPrettyPrinting().create().toJson(arguments));
    }

    private void load() {
        for (ServerConfig s : repo.load()) servers.addElement(s);
        if (servers.isEmpty()) servers.addElement(new ServerConfig());
        serverList.setSelectedIndex(0);
    }

    private void addServer() {
        ServerConfig s = new ServerConfig();
        s.name = "New Server";
        servers.addElement(s);
        serverList.setSelectedIndex(servers.size() - 1);
        persist();
    }

    private void deleteServer() {
        int i = serverList.getSelectedIndex();
        if (i >= 0) {
            servers.remove(i);
            persist();
        }
    }

    private void persist() {
        try {
            List<ServerConfig> l = Collections.list(servers.elements());
            repo.save(l);
        } catch (Exception e) {
            log("保存配置失败: " + e.getMessage());
        }
    }

    private void connect() {
        ServerConfig s = serverList.getSelectedValue();
        if (s == null) {
            error("请先选择 Server");
            return;
        }
        if ("http".equalsIgnoreCase(s.transport) && s.url.trim().isEmpty()) {
            error("HTTP 模式请先填写 URL");
            return;
        }
        if (!"http".equalsIgnoreCase(s.transport) && s.command.trim().isEmpty()) {
            error("Stdio 模式请先填写命令");
            return;
        }
        setConnecting();
        new SwingWorker<Void, String>() {
            protected Void doInBackground() {
                try {
                    if ("sse".equalsIgnoreCase(s.transport)) {
                        transport = new SseTransport(s.url, s.headers);
                    } else if ("http".equalsIgnoreCase(s.transport)) {
                        HttpTransport http = new HttpTransport(s.url, s.headers);
                        http.setTimeoutMillis(s.timeoutMillis);
                        transport = http;
                    } else {
                        transport =
                                new StdioTransport(
                                        s.command, s.args, s.workingDirectory, s.environment);
                    }
                    transport.setLogListener(x -> SwingUtilities.invokeLater(() -> log(x)));
                    transport.start();
                    client = new McpClient(transport);
                    client.setNotificationListener(
                            message ->
                                    SwingUtilities.invokeLater(
                                            () -> {
                                                log("Server notification: " + message);
                                                String method =
                                                        message.has("method")
                                                                ? message.get("method")
                                                                        .getAsString()
                                                                : "";
                                                if (method.contains("tools/list_changed"))
                                                    listTools();
                                                if (method.contains("resources/list_changed"))
                                                    listResources();
                                                if (method.contains("prompts/list_changed"))
                                                    listPrompts();
                                            }));
                    client.setTimeout(s.timeoutMillis);
                    client.setRetryPolicy(s.maxRetries, s.retryDelayMillis);
                    client.initialize();
                    SwingUtilities.invokeLater(() -> setConnected(true));
                    publish("已连接并完成 MCP 初始化");
                    listTools();
                } catch (Exception e) {
                    if (transport != null) {
                        transport.close();
                        transport = null;
                    }
                    client = null;
                    SwingUtilities.invokeLater(() -> setConnected(false));
                    publish("连接失败: " + e.getMessage());
                }
                return null;
            }

            protected void process(List<String> xs) {
                for (String x : xs) log(x);
            }
        }.execute();
    }

    private void testConnection() {
        ServerConfig s = serverList.getSelectedValue();
        if (s == null) {
            error("请先选择 Server");
            return;
        }
        new SwingWorker<Void, String>() {
            private McpTransport testTransport;

            protected Void doInBackground() {
                try {
                    if ("sse".equalsIgnoreCase(s.transport)) {
                        if (s.url.trim().isEmpty())
                            throw new IllegalArgumentException("SSE URL 不能为空");
                        testTransport = new SseTransport(s.url, s.headers);
                    } else if ("http".equalsIgnoreCase(s.transport)) {
                        if (s.url.trim().isEmpty())
                            throw new IllegalArgumentException("HTTP URL 不能为空");
                        testTransport = new HttpTransport(s.url, s.headers);
                    } else {
                        if (s.command.trim().isEmpty())
                            throw new IllegalArgumentException("Stdio 命令不能为空");
                        testTransport =
                                new StdioTransport(
                                        s.command, s.args, s.workingDirectory, s.environment);
                    }
                    testTransport.setLogListener(x -> publish(x));
                    testTransport.start();
                    McpClient testClient = new McpClient(testTransport);
                    testClient.setTimeout(s.timeoutMillis);
                    testClient.setRetryPolicy(s.maxRetries, s.retryDelayMillis);
                    testClient.initialize();
                    publish("测试连接成功：MCP 初始化完成");
                } catch (Exception e) {
                    publish("测试连接失败：" + e.getMessage());
                } finally {
                    if (testTransport != null) testTransport.close();
                }
                return null;
            }

            protected void process(List<String> messages) {
                for (String message : messages) log(message);
            }
        }.execute();
    }

    private void listTools() {
        if (client == null) {
            error("请先连接 Server");
            return;
        }
        new SwingWorker<List<ToolDefinition>, Void>() {
            protected List<ToolDefinition> doInBackground() throws Exception {
                return client.listTools();
            }

            protected void done() {
                try {
                    tools.removeAllItems();
                    for (ToolDefinition t : get()) tools.addItem(t);
                    showSelectedToolSchema();
                    log("Tools 列表已刷新");
                } catch (Exception e) {
                    error("获取 Tools 失败: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void showSelectedToolSchema() {
        ToolDefinition selected = (ToolDefinition) tools.getSelectedItem();
        if (selected == null || selected.inputSchema == null) {
            toolSchema.setText("该 Tool 未声明 inputSchema");
            return;
        }
        toolSchema.setText(
                new GsonBuilder().setPrettyPrinting().create().toJson(selected.inputSchema));
        toolSchema.setCaretPosition(0);
        buildDynamicForm(selected.inputSchema);
    }

    private void buildDynamicForm(JsonObject schema) {
        dynamicForm.removeAll();
        dynamicFields.clear();
        dynamicDefinitions.clear();
        dynamicRequired.clear();
        if (schema == null || !schema.has("properties")) {
            dynamicForm.add(new JLabel("该 Tool 没有可生成的 object 参数表单"));
            dynamicForm.revalidate();
            dynamicForm.repaint();
            return;
        }
        JsonObject properties = schema.getAsJsonObject("properties");
        Set<String> required = new HashSet<String>();
        if (schema.has("required")) {
            for (JsonElement element : schema.getAsJsonArray("required"))
                required.add(element.getAsString());
        }
        dynamicRequired.addAll(required);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 6, 4, 6);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        int row = 0;
        for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            String name = entry.getKey();
            JsonObject definition = entry.getValue().getAsJsonObject();
            constraints.gridx = 0;
            constraints.gridy = row;
            dynamicForm.add(new JLabel(name + (required.contains(name) ? " *" : "")), constraints);
            JComponent input = createDynamicInput(definition);
            constraints.gridx = 1;
            dynamicForm.add(input, constraints);
            dynamicFields.put(name, input);
            dynamicDefinitions.put(name, definition);
            row++;
        }
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        constraints.weighty = 1;
        dynamicForm.add(Box.createVerticalGlue(), constraints);
        dynamicForm.revalidate();
        dynamicForm.repaint();
    }

    private JComponent createDynamicInput(JsonObject definition) {
        if (definition.has("enum")) {
            JComboBox<String> select = new JComboBox<String>();
            for (JsonElement value : definition.getAsJsonArray("enum"))
                select.addItem(value.getAsString());
            if (definition.has("default"))
                select.setSelectedItem(definition.get("default").getAsString());
            select.setPreferredSize(new Dimension(280, 28));
            if (definition.has("description"))
                select.setToolTipText(definition.get("description").getAsString());
            return select;
        }
        String type = definition.has("type") ? definition.get("type").getAsString() : "string";
        if ("boolean".equals(type)) {
            JCheckBox checkBox = new JCheckBox();
            if (definition.has("default"))
                checkBox.setSelected(definition.get("default").getAsBoolean());
            if (definition.has("description"))
                checkBox.setToolTipText(definition.get("description").getAsString());
            return checkBox;
        }
        if ("array".equals(type) || "object".equals(type)) {
            JTextArea json = new JTextArea("array".equals(type) ? "[]" : "{}", 3, 28);
            json.setFont(new Font("Monospaced", Font.PLAIN, 13));
            json.setForeground(Color.BLACK);
            json.setBackground(Color.WHITE);
            json.setCaretColor(Color.BLACK);
            json.putClientProperty("jsonType", type);
            if (definition.has("description"))
                json.setToolTipText(definition.get("description").getAsString());
            return json;
        }
        JTextField field = new JTextField();
        field.setPreferredSize(new Dimension(280, 28));
        field.putClientProperty("jsonType", type);
        if (definition.has("default")) field.setText(definition.get("default").getAsString());
        if (definition.has("description"))
            field.setToolTipText(definition.get("description").getAsString());
        return field;
    }

    private void generateJsonFromForm() {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JComponent> entry : dynamicFields.entrySet()) {
            JComponent input = entry.getValue();
            String fieldName = entry.getKey();
            if (input instanceof JCheckBox) {
                result.addProperty(fieldName, ((JCheckBox) input).isSelected());
            } else {
                String value;
                if (input instanceof JComboBox) {
                    value = String.valueOf(((JComboBox<?>) input).getSelectedItem());
                } else if (input instanceof JTextArea) {
                    value = ((JTextArea) input).getText();
                } else {
                    value = ((JTextField) input).getText();
                }
                if (dynamicRequired.contains(fieldName)
                        && (value == null || value.trim().isEmpty())) {
                    error("必填字段不能为空: " + fieldName);
                    return;
                }
                String type = String.valueOf(input.getClientProperty("jsonType"));
                try {
                    JsonObject definition = dynamicDefinitions.get(fieldName);
                    if ("string".equals(type)) {
                        if (definition.has("minLength")
                                && value.length() < definition.get("minLength").getAsInt())
                            throw new IllegalArgumentException("长度小于 minLength");
                        if (definition.has("maxLength")
                                && value.length() > definition.get("maxLength").getAsInt())
                            throw new IllegalArgumentException("长度超过 maxLength");
                        if (definition.has("pattern")
                                && !value.matches(definition.get("pattern").getAsString()))
                            throw new IllegalArgumentException("不匹配 pattern");
                    }
                    if ("number".equals(type) || "integer".equals(type)) {
                        JsonElement number = new JsonParser().parse(value);
                        double numericValue = number.getAsDouble();
                        if (definition.has("minimum")
                                && numericValue < definition.get("minimum").getAsDouble())
                            throw new IllegalArgumentException("小于 minimum");
                        if (definition.has("maximum")
                                && numericValue > definition.get("maximum").getAsDouble())
                            throw new IllegalArgumentException("大于 maximum");
                        result.add(fieldName, number);
                    } else if ("array".equals(type) || "object".equals(type)) {
                        result.add(fieldName, new JsonParser().parse(value));
                    } else result.addProperty(fieldName, value);
                } catch (Exception e) {
                    error("字段 " + fieldName + " 校验失败: " + e.getMessage());
                    return;
                }
            }
        }
        args.setText(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    private void callTool() {
        ToolDefinition t = (ToolDefinition) tools.getSelectedItem();
        if (t == null || client == null) return;
        try {
            JsonElement a = new JsonParser().parse(args.getText());
            toolResult.setText("调用中...");
            long startedAt = System.currentTimeMillis();
            SwingWorker<JsonObject, Void> worker =
                    new SwingWorker<JsonObject, Void>() {
                        protected JsonObject doInBackground() throws Exception {
                            return client.callTool(t.name, a);
                        }

                        protected void done() {
                            try {
                                String response =
                                        new GsonBuilder()
                                                .setPrettyPrinting()
                                                .create()
                                                .toJson(get());
                                toolResult.setText(response);
                                long elapsed = System.currentTimeMillis() - startedAt;
                                callHistory.addElement(
                                        new Date() + "  " + t.name + "  " + elapsed + " ms");
                                callHistoryArgs.add(
                                        new GsonBuilder().setPrettyPrinting().create().toJson(a));
                                log("Tool 调用成功，耗时 " + elapsed + " ms");
                            } catch (Exception e) {
                                toolResult.setText("调用失败：" + e.getMessage());
                                callHistory.addElement(new Date() + "  " + t.name + "  失败");
                                callHistoryArgs.add(
                                        new GsonBuilder().setPrettyPrinting().create().toJson(a));
                                error("调用失败: " + e.getMessage());
                            }
                        }
                    };
            activeToolRequest = worker;
            worker.execute();
        } catch (Exception e) {
            error("参数 JSON 无效: " + e.getMessage());
        }
    }

    private void cancelToolCall() {
        if (activeToolRequest != null && !activeToolRequest.isDone()) {
            activeToolRequest.cancel(true);
            activeToolRequest = null;
            toolResult.setText("调用已取消");
            log("Tool 调用已取消");
        }
    }

    private void listResources() {
        if (client == null) {
            error("请先连接 Server");
            return;
        }
        new SwingWorker<List<ResourceDefinition>, Void>() {
            protected List<ResourceDefinition> doInBackground() throws Exception {
                return client.listResources();
            }

            protected void done() {
                try {
                    resources.removeAllItems();
                    for (ResourceDefinition resource : get()) resources.addItem(resource);
                    log("Resources 列表已刷新");
                } catch (Exception e) {
                    error("获取 Resources 失败: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void readResource(JTextArea result) {
        ResourceDefinition resource = (ResourceDefinition) resources.getSelectedItem();
        if (resource == null || client == null) return;
        new SwingWorker<JsonObject, Void>() {
            protected JsonObject doInBackground() throws Exception {
                return client.readResource(resource.uri);
            }

            protected void done() {
                try {
                    result.setText(new GsonBuilder().setPrettyPrinting().create().toJson(get()));
                } catch (Exception e) {
                    error("读取 Resource 失败: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void listPrompts() {
        if (client == null) {
            error("请先连接 Server");
            return;
        }
        new SwingWorker<List<PromptDefinition>, Void>() {
            protected List<PromptDefinition> doInBackground() throws Exception {
                return client.listPrompts();
            }

            protected void done() {
                try {
                    prompts.removeAllItems();
                    for (PromptDefinition prompt : get()) prompts.addItem(prompt);
                    showSelectedPromptArguments();
                    log("Prompts 列表已刷新");
                } catch (Exception e) {
                    error("获取 Prompts 失败: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void getPrompt(JTextArea input, JTextArea output) {
        PromptDefinition prompt = (PromptDefinition) prompts.getSelectedItem();
        if (prompt == null || client == null) return;
        try {
            JsonElement arguments = new JsonParser().parse(input.getText());
            new SwingWorker<JsonObject, Void>() {
                protected JsonObject doInBackground() throws Exception {
                    return client.getPrompt(prompt.name, arguments);
                }

                protected void done() {
                    try {
                        output.setText(
                                new GsonBuilder().setPrettyPrinting().create().toJson(get()));
                    } catch (Exception e) {
                        error("获取 Prompt 失败: " + e.getMessage());
                    }
                }
            }.execute();
        } catch (Exception e) {
            error("参数 JSON 无效: " + e.getMessage());
        }
    }

    private void disconnect() {
        if (transport != null) {
            transport.close();
            transport = null;
            client = null;
            setConnected(false);
            log("已断开连接");
        }
    }

    private void log(String s) {
        log.append(new Date() + "  " + s + "\n");
        if (log.getDocument().getLength() > 2_000_000) {
            log.replaceRange("", 0, log.getDocument().getLength() - 1_500_000);
        }
        Object autoScroll = log.getClientProperty("autoScroll");
        if (!Boolean.FALSE.equals(autoScroll)) log.setCaretPosition(log.getDocument().getLength());
    }

    private void error(String s) {
        log(s);
        JOptionPane.showMessageDialog(this, s, "错误", JOptionPane.ERROR_MESSAGE);
    }

    private void setConnected(boolean connected) {
        if (connected) {
            connectionStatus.setText("● 已连接");
            connectionStatus.setForeground(new Color(30, 140, 65));
        } else {
            connectionStatus.setText("● 未连接");
            connectionStatus.setForeground(new Color(180, 40, 40));
        }
    }

    private void setConnecting() {
        connectionStatus.setText("● 连接中...");
        connectionStatus.setForeground(new Color(190, 120, 20));
    }
}
