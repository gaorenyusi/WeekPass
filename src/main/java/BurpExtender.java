import burp.*;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BurpExtender implements IBurpExtender, ITab, IContextMenuFactory {
    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;

    private JPanel mainPanel;
    private JTextArea requestArea;
    private JComboBox<String> encodeBox;
    private JTable passTable, userTable;
    private DefaultTableModel passTableModel, userTableModel;
    private JProgressBar passProgress, userProgress;
    private JLabel passLabel, userLabel;
    private IHttpService currentService;

    private final String userDictPath = "/姓名拼音.txt";
    private final String passDictPath = "/top2000.txt";

    private ExecutorService executor;
    private volatile boolean pauseFlag = false;

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
        this.callbacks = callbacks;
        this.helpers = callbacks.getHelpers();

        callbacks.setExtensionName("weekpass");
        callbacks.printOutput("Welcome to WeekPass, now v.1.0");
        callbacks.registerContextMenuFactory(this);

        SwingUtilities.invokeLater(() -> {
            mainPanel = new JPanel(new BorderLayout());

            JPanel topPanel = new JPanel(new BorderLayout());
            requestArea = new JTextArea(10, 80);
            topPanel.add(new JScrollPane(requestArea), BorderLayout.CENTER);
            JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            encodeBox = new JComboBox<>(new String[]{"none", "md5", "base64"});
            JButton startBtn = new JButton("开始爆破");
            JButton pauseBtn = new JButton("暂停");
            JButton addUserPlaceholder = new JButton("添加$$");
            JButton addPassPlaceholder = new JButton("添加%%");
            JButton clearBtn = new JButton("清空结果");
            controlPanel.add(new JLabel("编码:"));
            controlPanel.add(encodeBox);
            controlPanel.add(startBtn);
            controlPanel.add(pauseBtn);
            controlPanel.add(addUserPlaceholder);
            controlPanel.add(addPassPlaceholder);
            controlPanel.add(clearBtn);
            topPanel.add(controlPanel, BorderLayout.SOUTH);
            mainPanel.add(topPanel, BorderLayout.NORTH);


            JPanel progressPanel = new JPanel(new GridLayout(2, 2));
            passLabel = new JLabel("密码爆破进度: 0/0");
            passProgress = createStyledProgressBar();
            userLabel = new JLabel("用户名爆破进度: 0/0");
            userProgress = createStyledProgressBar();
            progressPanel.add(passLabel);
            progressPanel.add(passProgress);
            progressPanel.add(userLabel);
            progressPanel.add(userProgress);
            mainPanel.add(progressPanel, BorderLayout.CENTER);


            JTabbedPane resultTabs = new JTabbedPane();
            passTableModel = new DefaultTableModel(new Object[]{"用户名", "密码(编码)", "状态码", "长度"}, 0);
            passTable = new JTable(passTableModel);
            userTableModel = new DefaultTableModel(new Object[]{"用户名", "密码(编码)", "状态码", "长度"}, 0);
            userTable = new JTable(userTableModel);
            TableRowSorter<DefaultTableModel> passSorter = new TableRowSorter<>(passTableModel);
            passSorter.setComparator(3, Comparator.reverseOrder());
            passTable.setRowSorter(passSorter);

            TableRowSorter<DefaultTableModel> userSorter = new TableRowSorter<>(userTableModel);
            userSorter.setComparator(3, Comparator.reverseOrder());
            userTable.setRowSorter(userSorter);

            resultTabs.addTab("密码爆破结果", new JScrollPane(passTable));
            resultTabs.addTab("用户名爆破结果", new JScrollPane(userTable));

            mainPanel.add(resultTabs, BorderLayout.SOUTH);
            startBtn.addActionListener(e -> startBruteForce());
            pauseBtn.addActionListener(e -> togglePause(pauseBtn));
            addUserPlaceholder.addActionListener(e -> addPlaceholder("$"));
            addPassPlaceholder.addActionListener(e -> addPlaceholder("%"));
            clearBtn.addActionListener(e -> clearResults());

            callbacks.addSuiteTab(BurpExtender.this);
        });
    }

    @Override
    public String getTabCaption() {
        return "weekpass";
    }

    @Override
    public Component getUiComponent() {
        return mainPanel;
    }

    @Override
    public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
        List<JMenuItem> menu = new ArrayList<>();
        JMenuItem sendToWeekpass = new JMenuItem("send to weekpass");
        sendToWeekpass.addActionListener(e -> {
            IHttpRequestResponse message = invocation.getSelectedMessages()[0];
            currentService = message.getHttpService();
            byte[] request = message.getRequest();
            requestArea.setText(new String(request));
        });
        menu.add(sendToWeekpass);
        return menu;
    }

    private void startBruteForce() {
        passTableModel.setRowCount(0);
        userTableModel.setRowCount(0);

        String requestTemplate = requestArea.getText();
        if (requestTemplate.isEmpty()) {
            JOptionPane.showMessageDialog(mainPanel, "请输入请求包或从Repeater发送");
            return;
        }

        String fixedUser = extractBetween(requestTemplate, "\\$(.*?)\\$");
        String fixedPass = extractBetween(requestTemplate, "%(.*?)%");
        if (fixedUser == null || fixedPass == null) {
            JOptionPane.showMessageDialog(mainPanel, "请求中需包含 $固定用户名$ 和 %固定密码%");
            return;
        }

        if (currentService == null) {
            currentService = parseHttpService(requestTemplate);
        }

        List<String> users = loadDict(userDictPath);
        List<String> passes = loadDict(passDictPath);
        String encoding = (String) encodeBox.getSelectedItem();

        executor = Executors.newFixedThreadPool(10);
        pauseFlag = false;

        passProgress.setMaximum(passes.size());
        passProgress.setValue(0);
        userProgress.setMaximum(users.size());
        userProgress.setValue(0);
        passLabel.setText(String.format("密码爆破进度: 0/%d", passes.size()));
        userLabel.setText(String.format("用户名爆破进度: 0/%d", users.size()));
        for (String pass : passes) {
            String encodedPass = encodeValue(pass, encoding);
            String req = requestTemplate.replace("%" + fixedPass + "%", encodedPass)
                    .replace("$" + fixedUser + "$", fixedUser);
            executor.submit(() -> {
                waitIfPaused();
                sendAndRecord(req, fixedUser, encodedPass, passTableModel);
                SwingUtilities.invokeLater(() -> updateProgress(passProgress, passLabel, passes.size()));
            });
        }
        for (String user : users) {
            String encodedPass = encodeValue(fixedPass, encoding);
            String req = requestTemplate.replace("$" + fixedUser + "$", user)
                    .replace("%" + fixedPass + "%", encodedPass);
            executor.submit(() -> {
                waitIfPaused();
                sendAndRecord(req, user, encodedPass, userTableModel);
                SwingUtilities.invokeLater(() -> updateProgress(userProgress, userLabel, users.size()));
            });
        }
    }

    private void togglePause(JButton pauseBtn) {
        pauseFlag = !pauseFlag;
        if (!pauseFlag) {
            synchronized (this) {
                this.notifyAll();
            }
            pauseBtn.setText("暂停");
        } else {
            pauseBtn.setText("继续");
        }
    }

    private void waitIfPaused() {
        synchronized (this) {
            while (pauseFlag) {
                try {
                    this.wait();
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private void clearResults() {
        passTableModel.setRowCount(0);
        userTableModel.setRowCount(0);
        passProgress.setValue(0);
        userProgress.setValue(0);
        passLabel.setText("密码爆破进度: 0/0");
        userLabel.setText("用户名爆破进度: 0/0");
    }

    private void updateProgress(JProgressBar bar, JLabel label, int total) {
        int value = bar.getValue() + 1;
        bar.setValue(value);
        int percent = (int) ((value / (double) total) * 100);
        bar.setString(percent + "%");
        label.setText(String.format("%s: %d/%d", label.getText().contains("密码") ? "密码爆破进度" : "用户名爆破进度", value, total));
    }

    private JProgressBar createStyledProgressBar() {
        JProgressBar pb = new JProgressBar(0, 100);
        pb.setStringPainted(true);
        pb.setForeground(new Color(0, 128, 0));
        pb.setUI(new javax.swing.plaf.basic.BasicProgressBarUI() {
            protected Color getSelectionBackground() { return Color.BLACK; }
            protected Color getSelectionForeground() { return Color.WHITE; }
        });
        return pb;
    }

    private void addPlaceholder(String symbol) {
        String selected = requestArea.getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            requestArea.replaceSelection(symbol + selected + symbol);
        }
    }

    private String extractBetween(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private IHttpService parseHttpService(String request) {
        String host = null;
        int port = 80;
        boolean https = false;

        for (String line : request.split("\r?\n")) {
            if (line.toLowerCase().startsWith("host:")) {
                String[] parts = line.split(":");
                host = parts[1].trim();
                if (parts.length > 2) {
                    port = Integer.parseInt(parts[2].trim());
                }
            }
        }
        if (request.toLowerCase().contains("https")) {
            https = true;
            if (port == 80) port = 443;
        }
        return callbacks.getHelpers().buildHttpService(host, port, https ? "https" : "http");
    }

    private void sendAndRecord(String request, String user, String pass, DefaultTableModel model) {
        try {
            byte[] respBytes = callbacks.makeHttpRequest(currentService, request.getBytes()).getResponse();
            if (respBytes != null) {
                IResponseInfo respInfo = helpers.analyzeResponse(respBytes);
                int statusCode = respInfo.getStatusCode();
                int length = respBytes.length;
                SwingUtilities.invokeLater(() ->
                        model.addRow(new Object[]{user, pass, statusCode, length})
                );
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private List<String> loadDict(String resourcePath) {
        List<String> list = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream(resourcePath);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            if (in == null) {
                throw new FileNotFoundException("资源文件未找到: " + resourcePath);
            }

            String line;
            while ((line = br.readLine()) != null) {
                list.add(line.trim());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return list;
    }

    private String encodeValue(String value, String encoding) {
        try {
            if ("md5".equalsIgnoreCase(encoding)) {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(value.getBytes());
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b & 0xff));
                }
                return sb.toString();
            } else if ("base64".equalsIgnoreCase(encoding)) {
                return Base64.getEncoder().encodeToString(value.getBytes());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return value;
    }
}
