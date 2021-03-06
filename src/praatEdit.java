
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.UIDefaults;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;
import praat.format;

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
/**
 *
 * @author David Lukes
 */
public class praatEdit extends javax.swing.JFrame implements PropertyChangeListener {

    // GLOBAL VARIABLES:
    // I thought I needed to take care of the different line separators across
    // OSs myself, but I probably don't (because the following commented out code
    // actually ADDS bugs on Windows instead of ensuring that the program runs
    // correctly); thank you Java, good to know.
//    private static final String lineSep = System.getProperty("line.separator");
//    private static final int lineSepLen = lineSep.length();
    private static final String lineSep = "\n";
    private static final int lineSepLen = 1;
    // keep track of personal settings:
    private static int fontSize = 14;
    // keep track of whether document should be saved before closing:
    private Boolean documentModified = false;
    // stuff for opening and saving files:
    private File file = null;
    private File backup;
    // stuff for autoindenting in insertString:
    private static String tab = "    ";
    private static int tabLen = tab.length();
    private static final String praatAutoIndent = "^\\s*(for|if|procedure|form"
            + "|while|editor|elsif)(?: .*)?";
    private static final String praatAutoDeindent = "^\\s*(endfor|elsif|endif"
            + "|endproc|endform|endwhile|endeditor)(?: .*)?";
    private static final Pattern praatAutoDeindentPattern = Pattern.compile(praatAutoDeindent);
    private static final Pattern praatSpacePattern = Pattern.compile("^(\\s*).*");
    // stuff for search and replace:
    private static int searchOffset = 0;
    private static int replaceOffset = 0;
    private static final StyleContext cont = StyleContext.getDefaultStyleContext();
    private static final AttributeSet searchHighlight = cont.addAttribute(
            cont.getEmptySet(), StyleConstants.Background, praat.colors.HIGHLIGHT);
    private static final AttributeSet searchNoHighlight = cont.addAttribute(
            cont.getEmptySet(), StyleConstants.Background, Color.DARK_GRAY);
    // stuff for undo/redo:
//    protected PraatUndoManager undoRedo = new PraatUndoManager();
    protected UndoManager undoRedo = new UndoManager();
    private final UndoableEditListener unEdList = new PraatUndoRedo();
    private static final String lineSepRegex = "(\\r\\n|\\r|\\n)";
    private static final Pattern lineSepPattern = Pattern.compile(lineSepRegex);
    // stuff for line numbers:
    private JTextPane lines;
    private String lineNumbers;
    private int lineCount = 1;
    private PraatDocument doc = new PraatDocument();
    private OpenFileTask openTask;
    private String filename;
    private Boolean openingFile = false;
    private Boolean doingUndoRedo = false;
    private static final String fileSep = System.getProperty("file.separator");
    private static final String userHome = System.getProperty("user.home");
    // location of sendpraat and the Praat config dir initialized to their default values (which are subject 
    // to change by the user if he's in a messy Windows networked setup where
    // his home dir is unclear):
    private String spLocation = userHome;
    private static final Boolean osIsWindows =
            System.getProperty("os.name").toLowerCase().indexOf("win") >= 0;
    private static String praatDir = userHome + fileSep
            + (osIsWindows ? "Praat" : ".praat-dir");
    private static String lastFileChooseDir = userHome;
    // by contrast, the location of .praateditrc cannot change (or else we run into a bootstrapping
    // paradox):
    private static final String rcLocation = userHome;
    private static final String praatEditRCStr = rcLocation + fileSep + ".praateditrc";
    private static final File praatEditRC = new java.io.File(praatEditRCStr);
//    // a stack which remembers 100 undos:
//    private FixedStack<UndoableEdit> undoFixedStack;
//    private FixedStack<UndoableEdit> redoFixedStack;
    // stuff for character encodings:
    private static Boolean utf8BomFlag = false;
    private static final String UTF8_BOM = "\uFEFF";
    private static String encoding = "UTF-8";
    private static final String currentLocale = System.getProperty("file.encoding");
    private static String fontName = "Courier New";

    /**
     * Creates new form praatEdit
     */
    public praatEdit() {
        initComponents();

        // a window listener for exiting gracefully:
        mainWindowListener();

        // a listener to dismiss the search and replace dialog with escape:
        searchAndReplaceEscapeListener(searchAndReplaceDialog);

        // set up line numbers in the scrollPane's row header:
        lines = new JTextPane();
        lines.setFont(new java.awt.Font(fontName, 0, 14));
        lines.setText("1");
        lines.setEditable(false);
        scrollPane.setRowHeaderView(lines);

        // set up the styled document in the textPane (for syntax highlighting etc.):
        textPane.setDocument(doc);

        // change bg colors of both text panes (for some reason, Look and
        // Feel settings may override this, so they in turn need to be overridden):
        UIDefaults linesDefaults = new UIDefaults();
        UIDefaults textPaneDefaults = new UIDefaults();
        linesDefaults.put("TextPane[Enabled].backgroundPainter", Color.LIGHT_GRAY);
        textPaneDefaults.put("TextPane[Enabled].backgroundPainter", Color.DARK_GRAY);
        lines.putClientProperty("Nimbus.Overrides", linesDefaults);
        lines.putClientProperty("Nimbus.Overrides.InheritDefaults", true);
        lines.setBackground(Color.LIGHT_GRAY);
        lines.setForeground(Color.DARK_GRAY);
        textPane.putClientProperty("Nimbus.Overrides", textPaneDefaults);
        textPane.putClientProperty("Nimbus.Overrides.InheritDefaults", true);
        textPane.setBackground(Color.DARK_GRAY);

        // undo/redo:
        doc.addUndoableEditListener(unEdList);
//        undoFixedStack = new FixedStack<>(100);
//        redoFixedStack = new FixedStack<>(100);
        undoRedo.setLimit(10000);

        // load personal settings:
        loadPreferences();

        // load last fileChooser directory from last session:
        fileOpenSave.setCurrentDirectory(new File(lastFileChooseDir));
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        fileOpenSave = new javax.swing.JFileChooser();
        dirSelect = new javax.swing.JFileChooser();
        searchAndReplaceDialog = new javax.swing.JDialog();
        replaceTextField = new javax.swing.JTextField();
        findTextField = new javax.swing.JTextField();
        jLabel1 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        findNext = new javax.swing.JButton();
        findAllButton = new javax.swing.JButton();
        replaceAllButton = new javax.swing.JButton();
        checkBoxUseRegex = new javax.swing.JCheckBox();
        replaceCurrentButton = new javax.swing.JButton();
        fontSizeButtonGroup = new javax.swing.ButtonGroup();
        tabWidthButtonGroup = new javax.swing.ButtonGroup();
        readWriteEncButtonGroup = new javax.swing.ButtonGroup();
        loadingProgressFrame = new javax.swing.JDialog();
        loadingProgressBar = new javax.swing.JProgressBar();
        noWrapPanel = new javax.swing.JPanel();
        textPane = new javax.swing.JTextPane()         {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                /*
                * by overriding this method, line wrapping is disabled (which
                    * is needed so that line numbering doesn't get out of sync)
                */

                return getUI().getPreferredSize(this).width
                <= getParent().getSize().width;
            }
        };
        textPane.setCaretColor(Color.LIGHT_GRAY);
        setPathsFrame = new javax.swing.JDialog();
        jLabel4 = new javax.swing.JLabel();
        setPathSPTextField = new javax.swing.JTextField();
        setPathsConfirmButton = new javax.swing.JButton();
        setPathsSPChooseButton = new javax.swing.JButton();
        setPathsPCChooseButton = new javax.swing.JButton();
        jLabel3 = new javax.swing.JLabel();
        setPathPCTextField = new javax.swing.JTextField();
        scrollPane = scrollPane = new javax.swing.JScrollPane(noWrapPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(20);
        menuBar = new javax.swing.JMenuBar();
        menuFile = new javax.swing.JMenu();
        menuFileNew = new javax.swing.JMenuItem();
        menuFileOpen = new javax.swing.JMenuItem();
        menuFileSave = new javax.swing.JMenuItem();
        menuFileSaveAs = new javax.swing.JMenuItem();
        menuFileRun = new javax.swing.JMenuItem();
        jSeparator1 = new javax.swing.JPopupMenu.Separator();
        menuFileSaveNew = new javax.swing.JMenuItem();
        menuFileSaveOpen = new javax.swing.JMenuItem();
        menuFileSaveRun = new javax.swing.JMenuItem();
        menuEdit = new javax.swing.JMenu();
        menuEditUndo = new javax.swing.JMenuItem();
        menuEditRedo = new javax.swing.JMenuItem();
        jSeparator3 = new javax.swing.JPopupMenu.Separator();
        menuEditComment = new javax.swing.JMenuItem();
        menuEditUncomment = new javax.swing.JMenuItem();
        menuEditIndent = new javax.swing.JMenuItem();
        menuEditUnindent = new javax.swing.JMenuItem();
        jSeparator2 = new javax.swing.JPopupMenu.Separator();
        menuEditFind = new javax.swing.JMenuItem();
        menuEditUnhighlight = new javax.swing.JMenuItem();
        menuPreferences = new javax.swing.JMenu();
        checkBoxAutoIndent = new javax.swing.JCheckBoxMenuItem();
        menuPreferencesFontSize = new javax.swing.JMenu();
        radioFontSize10 = new javax.swing.JRadioButtonMenuItem();
        radioFontSize12 = new javax.swing.JRadioButtonMenuItem();
        radioFontSize14 = new javax.swing.JRadioButtonMenuItem();
        radioFontSize16 = new javax.swing.JRadioButtonMenuItem();
        radioFontSize18 = new javax.swing.JRadioButtonMenuItem();
        menuPreferencesTabWidth = new javax.swing.JMenu();
        radioTabWidth2 = new javax.swing.JRadioButtonMenuItem();
        radioTabWidth3 = new javax.swing.JRadioButtonMenuItem();
        radioTabWidth4 = new javax.swing.JRadioButtonMenuItem();
        readWriteEncoding = new javax.swing.JMenu();
        radioEncodingUTF8 = new javax.swing.JRadioButtonMenuItem();
        radioEncodingCurrLoc = new javax.swing.JRadioButtonMenuItem();
        jSeparator4 = new javax.swing.JPopupMenu.Separator();
        menuPreferencesSetPaths = new javax.swing.JMenuItem();

        fileOpenSave.setApproveButtonText("Open");
        fileOpenSave.setApproveButtonToolTipText("Open file as specified");
        fileOpenSave.setDialogTitle("Choose file...");

        dirSelect.setDialogTitle("Select directory");
        dirSelect.setFileHidingEnabled(false);
        dirSelect.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);

        searchAndReplaceDialog.setTitle("Search & Replace");
        searchAndReplaceDialog.setResizable(false);

        replaceTextField.addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyTyped(java.awt.event.KeyEvent evt) {
                replaceTextFieldKeyTyped(evt);
            }
        });

        findTextField.addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyTyped(java.awt.event.KeyEvent evt) {
                findTextFieldKeyTyped(evt);
            }
        });

        jLabel1.setLabelFor(findTextField);
        jLabel1.setText("Find:");

        jLabel2.setLabelFor(replaceTextField);
        jLabel2.setText("Replace with:");

        findNext.setMnemonic('n');
        findNext.setText("Find next");
        findNext.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                findNextActionPerformed(evt);
            }
        });

        findAllButton.setMnemonic('a');
        findAllButton.setText("Find all");
        findAllButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                findAllButtonActionPerformed(evt);
            }
        });

        replaceAllButton.setMnemonic('l');
        replaceAllButton.setText("Replace all");
        replaceAllButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                replaceAllButtonActionPerformed(evt);
            }
        });

        checkBoxUseRegex.setMnemonic('u');
        checkBoxUseRegex.setText("Use regular expressions");
        checkBoxUseRegex.setToolTipText("<html>\nOnly in Find box. No capturing<br>\nparentheses for Replace.\n</html>"); // NOI18N

        replaceCurrentButton.setMnemonic('r');
        replaceCurrentButton.setText("Replace");
        replaceCurrentButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                replaceCurrentButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout searchAndReplaceDialogLayout = new javax.swing.GroupLayout(searchAndReplaceDialog.getContentPane());
        searchAndReplaceDialog.getContentPane().setLayout(searchAndReplaceDialogLayout);
        searchAndReplaceDialogLayout.setHorizontalGroup(
            searchAndReplaceDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(searchAndReplaceDialogLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(searchAndReplaceDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(searchAndReplaceDialogLayout.createSequentialGroup()
                        .addGroup(searchAndReplaceDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(findTextField)
                            .addComponent(replaceTextField)
                            .addGroup(searchAndReplaceDialogLayout.createSequentialGroup()
                                .addComponent(jLabel1)
                                .addGap(129, 129, 129)))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(searchAndReplaceDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(searchAndReplaceDialogLayout.createSequentialGroup()
                                .addGroup(searchAndReplaceDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(findNext, javax.swing.GroupLayout.DEFAULT_SIZE, 107, Short.MAX_VALUE)
                                    .addComponent(replaceCurrentButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(searchAndReplaceDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(replaceAllButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                    .addComponent(findAllButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                            .addComponent(checkBoxUseRegex))
                        .addGap(12, 12, 12))
                    .addComponent(jLabel2)))
        );
        searchAndReplaceDialogLayout.setVerticalGroup(
            searchAndReplaceDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(searchAndReplaceDialogLayout.createSequentialGroup()
                .addGroup(searchAndReplaceDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(searchAndReplaceDialogLayout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(searchAndReplaceDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jLabel1)
                            .addComponent(checkBoxUseRegex))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(searchAndReplaceDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(findTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(findNext)
                            .addComponent(findAllButton))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jLabel2))
                    .addGroup(searchAndReplaceDialogLayout.createSequentialGroup()
                        .addGap(77, 77, 77)
                        .addGroup(searchAndReplaceDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(replaceAllButton)
                            .addComponent(replaceCurrentButton)
                            .addComponent(replaceTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))))
                .addContainerGap())
        );

        loadingProgressFrame.setTitle("Loading file");
        loadingProgressFrame.getContentPane().setLayout(new java.awt.CardLayout());

        loadingProgressBar.setString("Loading...");
        loadingProgressBar.setStringPainted(true);
        loadingProgressFrame.getContentPane().add(loadingProgressBar, "card2");

        loadingProgressFrame.pack();

        noWrapPanel.setLayout(new java.awt.BorderLayout());

        textPane.setFont(new java.awt.Font(fontName, 0, 14));
        textPane.setForeground(new java.awt.Color(204, 204, 204));
        textPane.setCursor(new java.awt.Cursor(java.awt.Cursor.TEXT_CURSOR));
        textPane.setDragEnabled(true);
        textPane.setDropMode(javax.swing.DropMode.INSERT);
        textPane.setMinimumSize(new java.awt.Dimension(6000, 2300));

        setPathsFrame.setTitle("Set paths...");
        setPathsFrame.setResizable(false);

        jLabel4.setText("Location of the sendpraat program:");

        setPathSPTextField.setEditable(false);
        setPathSPTextField.setText("jTextField1");
        setPathSPTextField.setFocusable(false);

        setPathsConfirmButton.setText("OK");
        setPathsConfirmButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                setPathsConfirmButtonActionPerformed(evt);
            }
        });

        setPathsSPChooseButton.setText("Choose...");
        setPathsSPChooseButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                setPathsSPChooseButtonActionPerformed(evt);
            }
        });

        setPathsPCChooseButton.setText("Choose...");
        setPathsPCChooseButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                setPathsPCChooseButtonActionPerformed(evt);
            }
        });

        jLabel3.setText("<html>Location of the Praat configuration directory (should<br>normally be found by default, change only if sending<br>to Praat doesn't work):</html>");

        setPathPCTextField.setEditable(false);
        setPathPCTextField.setText("jTextField1");
        setPathPCTextField.setFocusable(false);

        javax.swing.GroupLayout setPathsFrameLayout = new javax.swing.GroupLayout(setPathsFrame.getContentPane());
        setPathsFrame.getContentPane().setLayout(setPathsFrameLayout);
        setPathsFrameLayout.setHorizontalGroup(
            setPathsFrameLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(setPathsFrameLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(setPathsFrameLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(setPathsFrameLayout.createSequentialGroup()
                        .addComponent(setPathSPTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 254, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(setPathsSPChooseButton))
                    .addComponent(jLabel4)
                    .addComponent(jLabel3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(setPathsFrameLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                        .addComponent(setPathsConfirmButton)
                        .addGroup(setPathsFrameLayout.createSequentialGroup()
                            .addComponent(setPathPCTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 254, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(setPathsPCChooseButton))))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        setPathsFrameLayout.setVerticalGroup(
            setPathsFrameLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(setPathsFrameLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel4)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(setPathsFrameLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(setPathSPTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(setPathsSPChooseButton))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jLabel3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(setPathsFrameLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(setPathPCTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(setPathsPCChooseButton))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(setPathsConfirmButton)
                .addContainerGap())
        );

        setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        setTitle("PraatEdit");
        setMinimumSize(new java.awt.Dimension(640, 480));

        /*
        * the following ensures that the textPane doesn't wrap text when a new
        * file is loaded. the noWrapPanel is a JPanel with BorderLayout. the
        * scrollPane's ViewportView is set to the noWrapPanel instead of
        * directly to the textPane, which is then added to the noWrapPanel.
        */
        scrollPane.setViewportView(noWrapPanel);
        noWrapPanel.add(textPane);
        getContentPane().add(scrollPane, java.awt.BorderLayout.CENTER);

        menuFile.setMnemonic('F');
        menuFile.setText("File");

        menuFileNew.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, java.awt.event.InputEvent.CTRL_MASK));
        menuFileNew.setText("New");
        menuFileNew.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuFileNewActionPerformed(evt);
            }
        });
        menuFile.add(menuFileNew);

        menuFileOpen.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_MASK));
        menuFileOpen.setText("Open");
        menuFileOpen.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuFileOpenActionPerformed(evt);
            }
        });
        menuFile.add(menuFileOpen);

        menuFileSave.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_MASK));
        menuFileSave.setText("Save");
        menuFileSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuFileSaveActionPerformed(evt);
            }
        });
        menuFile.add(menuFileSave);

        menuFileSaveAs.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.SHIFT_MASK | java.awt.event.InputEvent.CTRL_MASK));
        menuFileSaveAs.setText("Save As");
        menuFileSaveAs.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuFileSaveAsActionPerformed(evt);
            }
        });
        menuFile.add(menuFileSaveAs);

        menuFileRun.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R, java.awt.event.InputEvent.CTRL_MASK));
        menuFileRun.setText("Run");
        menuFileRun.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuFileRunActionPerformed(evt);
            }
        });
        menuFile.add(menuFileRun);
        menuFile.add(jSeparator1);

        menuFileSaveNew.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, java.awt.event.InputEvent.ALT_MASK));
        menuFileSaveNew.setText("Save & New");
        menuFileSaveNew.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuFileSaveNewActionPerformed(evt);
            }
        });
        menuFile.add(menuFileSaveNew);

        menuFileSaveOpen.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.ALT_MASK));
        menuFileSaveOpen.setText("Save & Open");
        menuFileSaveOpen.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuFileSaveOpenActionPerformed(evt);
            }
        });
        menuFile.add(menuFileSaveOpen);

        menuFileSaveRun.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R, java.awt.event.InputEvent.ALT_MASK));
        menuFileSaveRun.setText("Save & Run");
        menuFileSaveRun.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuFileSaveRunActionPerformed(evt);
            }
        });
        menuFile.add(menuFileSaveRun);

        menuBar.add(menuFile);

        menuEdit.setMnemonic('E');
        menuEdit.setText("Edit");

        menuEditUndo.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_MASK));
        menuEditUndo.setText("Undo");
        menuEditUndo.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuEditUndoActionPerformed(evt);
            }
        });
        menuEdit.add(menuEditUndo);

        menuEditRedo.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_MASK));
        menuEditRedo.setText("Redo");
        menuEditRedo.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuEditRedoActionPerformed(evt);
            }
        });
        menuEdit.add(menuEditRedo);
        menuEdit.add(jSeparator3);

        menuEditComment.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_M, java.awt.event.InputEvent.CTRL_MASK));
        menuEditComment.setText("Comment");
        menuEditComment.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuEditCommentActionPerformed(evt);
            }
        });
        menuEdit.add(menuEditComment);

        menuEditUncomment.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_M, java.awt.event.InputEvent.SHIFT_MASK | java.awt.event.InputEvent.CTRL_MASK));
        menuEditUncomment.setText("Uncomment");
        menuEditUncomment.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuEditUncommentActionPerformed(evt);
            }
        });
        menuEdit.add(menuEditUncomment);

        menuEditIndent.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_I, java.awt.event.InputEvent.CTRL_MASK));
        menuEditIndent.setText("Indent");
        menuEditIndent.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuEditIndentActionPerformed(evt);
            }
        });
        menuEdit.add(menuEditIndent);

        menuEditUnindent.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_I, java.awt.event.InputEvent.SHIFT_MASK | java.awt.event.InputEvent.CTRL_MASK));
        menuEditUnindent.setText("Unindent");
        menuEditUnindent.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuEditUnindentActionPerformed(evt);
            }
        });
        menuEdit.add(menuEditUnindent);
        menuEdit.add(jSeparator2);

        menuEditFind.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_MASK));
        menuEditFind.setText("Find & Replace");
        menuEditFind.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuEditFindActionPerformed(evt);
            }
        });
        menuEdit.add(menuEditFind);

        menuEditUnhighlight.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, java.awt.event.InputEvent.SHIFT_MASK | java.awt.event.InputEvent.CTRL_MASK));
        menuEditUnhighlight.setText("Unhighlight");
        menuEditUnhighlight.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuEditUnhighlightActionPerformed(evt);
            }
        });
        menuEdit.add(menuEditUnhighlight);

        menuBar.add(menuEdit);

        menuPreferences.setMnemonic('P');
        menuPreferences.setText("Preferences");

        checkBoxAutoIndent.setSelected(true);
        checkBoxAutoIndent.setText("Auto-Indent");
        menuPreferences.add(checkBoxAutoIndent);

        menuPreferencesFontSize.setText("Font Size");

        fontSizeButtonGroup.add(radioFontSize10);
        radioFontSize10.setText("10");
        radioFontSize10.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioFontSize10ActionPerformed(evt);
            }
        });
        menuPreferencesFontSize.add(radioFontSize10);

        fontSizeButtonGroup.add(radioFontSize12);
        radioFontSize12.setText("12");
        radioFontSize12.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioFontSize12ActionPerformed(evt);
            }
        });
        menuPreferencesFontSize.add(radioFontSize12);

        fontSizeButtonGroup.add(radioFontSize14);
        radioFontSize14.setSelected(true);
        radioFontSize14.setText("14");
        radioFontSize14.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioFontSize14ActionPerformed(evt);
            }
        });
        menuPreferencesFontSize.add(radioFontSize14);

        fontSizeButtonGroup.add(radioFontSize16);
        radioFontSize16.setText("16");
        radioFontSize16.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioFontSize16ActionPerformed(evt);
            }
        });
        menuPreferencesFontSize.add(radioFontSize16);

        fontSizeButtonGroup.add(radioFontSize18);
        radioFontSize18.setText("18");
        radioFontSize18.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioFontSize18ActionPerformed(evt);
            }
        });
        menuPreferencesFontSize.add(radioFontSize18);

        menuPreferences.add(menuPreferencesFontSize);

        menuPreferencesTabWidth.setText("Tab Width");

        tabWidthButtonGroup.add(radioTabWidth2);
        radioTabWidth2.setText("2 spaces");
        radioTabWidth2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioTabWidth2ActionPerformed(evt);
            }
        });
        menuPreferencesTabWidth.add(radioTabWidth2);

        tabWidthButtonGroup.add(radioTabWidth3);
        radioTabWidth3.setText("3 spaces");
        radioTabWidth3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioTabWidth3ActionPerformed(evt);
            }
        });
        menuPreferencesTabWidth.add(radioTabWidth3);

        tabWidthButtonGroup.add(radioTabWidth4);
        radioTabWidth4.setSelected(true);
        radioTabWidth4.setText("4 spaces");
        radioTabWidth4.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioTabWidth4ActionPerformed(evt);
            }
        });
        menuPreferencesTabWidth.add(radioTabWidth4);

        menuPreferences.add(menuPreferencesTabWidth);

        readWriteEncoding.setText("Read/Write Encoding");

        readWriteEncButtonGroup.add(radioEncodingUTF8);
        radioEncodingUTF8.setSelected(true);
        radioEncodingUTF8.setText("UTF-8");
        radioEncodingUTF8.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioEncodingUTF8ActionPerformed(evt);
            }
        });
        readWriteEncoding.add(radioEncodingUTF8);

        readWriteEncButtonGroup.add(radioEncodingCurrLoc);
        radioEncodingCurrLoc.setText("Current Locale");
        radioEncodingCurrLoc.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                radioEncodingCurrLocActionPerformed(evt);
            }
        });
        readWriteEncoding.add(radioEncodingCurrLoc);

        menuPreferences.add(readWriteEncoding);
        menuPreferences.add(jSeparator4);

        menuPreferencesSetPaths.setText("Set paths...");
        menuPreferencesSetPaths.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                menuPreferencesSetPathsActionPerformed(evt);
            }
        });
        menuPreferences.add(menuPreferencesSetPaths);

        menuBar.add(menuPreferences);

        setJMenuBar(menuBar);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void menuFileOpenActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuFileOpenActionPerformed
        // TODO add your handling code here:
        if (documentModified) {
            Object[] options = {"Yes", "No", "Cancel"};
            int answer = JOptionPane.showOptionDialog(rootPane,
                    "File has been modified. If you open a new one,\nyou will"
                    + " lose your changes. Save before closing?", "Save changes?",
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[2]);
            switch (answer) {
                case 0:
                    saveFile(false);
                    break;
                case 1:
                    break;
                case 2:
                    return;
            }
        }
        openFile();
    }//GEN-LAST:event_menuFileOpenActionPerformed

    private void menuFileSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuFileSaveActionPerformed
        // TODO add your handling code here:
        saveFile(false);
    }//GEN-LAST:event_menuFileSaveActionPerformed

    private void menuFileSaveRunActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuFileSaveRunActionPerformed
        // TODO add your handling code here:
        saveFile(false);
        runFile();
    }//GEN-LAST:event_menuFileSaveRunActionPerformed

    private void menuFileNewActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuFileNewActionPerformed
        // TODO add your handling code here:
        if (documentModified) {
            Object[] options = {"Yes", "No", "Cancel"};
            int answer = JOptionPane.showOptionDialog(rootPane,
                    "File has been modified. If you open a new one,\nyou will"
                    + " lose your changes. Save before closing?", "Save changes?",
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[2]);
            switch (answer) {
                case 0:
                    saveFile(false);
                    break;
                case 1:
                    break;
                case 2:
                    return;
            }
        }
        try {
            doc.remove(0, doc.getLength());
            file = null;
            setTitle("PraatEdit");
            documentModified = false;
            utf8BomFlag = false;
        } catch (BadLocationException e) {
        }
    }//GEN-LAST:event_menuFileNewActionPerformed

    private void menuFileSaveOpenActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuFileSaveOpenActionPerformed
        // TODO add your handling code here:
        saveFile(false);
        openFile();
    }//GEN-LAST:event_menuFileSaveOpenActionPerformed

    private void menuFileSaveNewActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuFileSaveNewActionPerformed
        // TODO add your handling code here:
        saveFile(false);
        try {
            doc.remove(0, doc.getLength());
            file = null;
            documentModified = false;
            utf8BomFlag = false;
        } catch (BadLocationException e) {
        }
    }//GEN-LAST:event_menuFileSaveNewActionPerformed

    private void menuFileRunActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuFileRunActionPerformed
        // TODO add your handling code here:
//        if (documentModified) {
//            Object[] options = {"Yes", "No"};
//            int answer = JOptionPane.showOptionDialog(rootPane,
//                    "Script must be saved before being run. Save and run?", "Save script?",
//                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
//                    null, options, options[1]);
//            switch (answer) {
//                case 0:
//                    saveFile(false);
//                    runFile();
//                    break;
//                case 1:
//            }
//        } else {
        runFile();
//        }
    }//GEN-LAST:event_menuFileRunActionPerformed

    private void menuEditCommentActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuEditCommentActionPerformed
        // TODO add your handling code here:
        prepend("# ");
    }//GEN-LAST:event_menuEditCommentActionPerformed

    private void menuEditIndentActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuEditIndentActionPerformed
        // TODO add your handling code here:
        prepend(tab);
    }//GEN-LAST:event_menuEditIndentActionPerformed

    private void menuEditUncommentActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuEditUncommentActionPerformed
        // TODO add your handling code here:
        unprepend("# ");
    }//GEN-LAST:event_menuEditUncommentActionPerformed

    private void menuEditUnindentActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuEditUnindentActionPerformed
        // TODO add your handling code here:
        unprepend(tab);
    }//GEN-LAST:event_menuEditUnindentActionPerformed

    private void menuEditFindActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuEditFindActionPerformed
        searchAndReplaceDialog.setLocationRelativeTo(rootPane);
        searchAndReplaceDialog.pack();
        // TODO add your handling code here:
        findTextField.setSelectionStart(0);
        findTextField.setSelectionEnd(findTextField.getText().length());
        replaceTextField.setSelectionStart(0);
        replaceTextField.setSelectionEnd(replaceTextField.getText().length());
        findTextField.grabFocus();
        // reset replaceOffset to -1 (we need to find sth first before doing
        // replaceCurrent):
        replaceOffset = -1;
        searchAndReplaceDialog.setVisible(true);
    }//GEN-LAST:event_menuEditFindActionPerformed

    private void menuEditUnhighlightActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuEditUnhighlightActionPerformed
        // TODO add your handling code here:
        doc.setCharacterAttributes(0, doc.getLength() + 1, searchNoHighlight, false);
    }//GEN-LAST:event_menuEditUnhighlightActionPerformed

    private void replaceCurrentButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_replaceCurrentButtonActionPerformed
        // TODO add your handling code here:
        if (replaceOffset < 0) {
            JOptionPane.showMessageDialog(searchAndReplaceDialog, "You must search for something first.", "Search first", JOptionPane.INFORMATION_MESSAGE);
        } else {
            try {
                String text = doc.getText(0, doc.getLength());
                String newStr = replaceTextField.getText();
                String oldStr = findTextField.getText();
                int newStrLen = newStr.length();
                int[] match = doMatch(text, oldStr, replaceOffset);
                if (match[0] > -1) {
                    doc.remove(match[0], match[1]);
                    // we cannot set the char attr of the string upon insert, because
                    // insertString strips all highlighting of background...
                    doc.insertString(match[0], newStr, null);
                    // ... so we have to do it post hoc here:
                    doc.setCharacterAttributes(match[0], newStrLen, searchHighlight, false);
                    // the current searchOffset will serve as replaceOffset for
                    // replaceCurrent:
                }
            } catch (BadLocationException e) {
            }
        }
        replaceOffset = -1;
    }//GEN-LAST:event_replaceCurrentButtonActionPerformed

    private void replaceAllButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_replaceAllButtonActionPerformed
        // TODO add your handling code here:
        String oldStr = findTextField.getText();
        String newStr = replaceTextField.getText();
        if (oldStr.length() > 0) {
            newStr = treatEscapeSequences(newStr);
            int docLen = doc.getLength();
            doc.setCharacterAttributes(0, docLen, searchNoHighlight, false);
            // reset searchOffset (we need to start the search from the beginning here):
            searchOffset = 0;
            Boolean matchFound = true;
            while (matchFound) {
                matchFound = replaceNext(oldStr, newStr, docLen, false);
                // the docLen is changing with each replace, we need to update it:
                docLen = doc.getLength();
            }
        } else {
            JOptionPane.showMessageDialog(searchAndReplaceDialog, "Find box is empty, cannot replace.", "Search first", JOptionPane.INFORMATION_MESSAGE);
        }
    }//GEN-LAST:event_replaceAllButtonActionPerformed

    private void findAllButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_findAllButtonActionPerformed
        // TODO add your handling code here:
        String str = findTextField.getText();
        if (str.length() > 0) {
            int docLen = doc.getLength();
            doc.setCharacterAttributes(0, docLen, searchNoHighlight, false);
            // reset searchOffset (we need to start the search from the beginning here):
            searchOffset = 0;
            Boolean matchFound = true;
            while (matchFound) {
                matchFound = findNext(str, docLen, false);
            }
        } else {
            JOptionPane.showMessageDialog(searchAndReplaceDialog, "Find box is empty, nothing to find.", "Search first", JOptionPane.INFORMATION_MESSAGE);
        }
    }//GEN-LAST:event_findAllButtonActionPerformed

    private void findNextActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_findNextActionPerformed
        // TODO add your handling code here:
        String str = findTextField.getText();
        int docLen = doc.getLength();
        doc.setCharacterAttributes(0, docLen, searchNoHighlight, false);
        findNext(str, docLen, true);
    }//GEN-LAST:event_findNextActionPerformed

    private void findTextFieldKeyTyped(java.awt.event.KeyEvent evt) {//GEN-FIRST:event_findTextFieldKeyTyped
        // TODO add your handling code here:
//        int typedChar = (int) evt.getKeyChar();
////        System.err.println(ch);
//        // detect if Enter was pressed:
//        if (typedChar == 10) {
//            findNextActionPerformed(null);
//        }
        searchOffset = textPane.getSelectionStart();
    }//GEN-LAST:event_findTextFieldKeyTyped

    private void replaceTextFieldKeyTyped(java.awt.event.KeyEvent evt) {//GEN-FIRST:event_replaceTextFieldKeyTyped
        // TODO add your handling code here:
        searchOffset = textPane.getSelectionStart();
    }//GEN-LAST:event_replaceTextFieldKeyTyped

    /*
     * undo/redo beyond the beginning of the document throw BadLocationExceptions,
     * which is presumably related to the fact that the undoManager gets unattached
     * from the document at times, so the edits don't quite add up. once identifying
     * "milestone" edits has been implemented (and the de-attaching/re-attaching
     * code is scrapped), this probably shouldn't cause any problems. for now, let's
     * at least check doc.getLength() as well in each undo/redo condition; it doesn't
     * solve the problem, but it prevents PraatEdit from hanging.
     * 
     * ALSO, LINE NUMBERS NOT UPDATING DURING UNDO/REDO!
     */
    private void menuEditRedoActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuEditRedoActionPerformed
        // TODO add your handling code here:
        // broken code trying to implement smarter undo/redo:
//        if (undoRedo.canRedo() && doc.getLength() >= 0) {
//            UndoableEdit redoneEdit = redoFixedStack.pop();
//            undoRedo.praatRedoTo(redoneEdit);
//            undoFixedStack.push(redoneEdit);
//            // update the line numbers:
//            doingUndoRedo = true;
//            lineCount = 1;
//            updateLineNumbers(textPane.getText(), true);
//            doingUndoRedo = false;
//        }
        while (undoRedo.canRedo() && undoRedo.getRedoPresentationName().contains("style")) {
//            while (redoingOnlyHighlighting) {
//            System.err.println(undoRedo.getUndoPresentationName());
            undoRedo.redo();
//            System.err.println(undoRedo.canUndo());
        }
        // and now make the addition:
        if (undoRedo.canRedo()) {
//            System.err.println("Addition undone");
            undoRedo.redo();
        }
        // perform any additional syntax highlighting that might have occurred
        // after inserting the string:
        while (undoRedo.canRedo() && undoRedo.getRedoPresentationName().contains("style")) {
            undoRedo.redo();
        }
        // update the line numbers:
        doingUndoRedo = true;
        lineCount = 1;
        updateLineNumbers(textPane.getText(), true);
        doingUndoRedo = false;
    }//GEN-LAST:event_menuEditRedoActionPerformed

    private void menuEditUndoActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuEditUndoActionPerformed
        // TODO add your handling code here:
        // broken code trying to implement smarter undo/redo:
//        if (undoRedo.canUndo() && doc.getLength() > 0) {
//            UndoableEdit undoneEdit = undoFixedStack.pop();
//            undoRedo.praatUndoTo(undoneEdit);
//            redoFixedStack.push(undoneEdit);
//            // update the line numbers:
//            doingUndoRedo = true;
//            lineCount = 1;
//            updateLineNumbers(textPane.getText(), true);
//            doingUndoRedo = false;
//        }
        // remove all style changes:
        while (undoRedo.canUndo() && undoRedo.getUndoPresentationName().contains("style")) {
//            while (redoingOnlyHighlighting) {
//            System.err.println(undoRedo.getUndoPresentationName());
            undoRedo.undo();
//            System.err.println(undoRedo.canUndo());
        }
        // and now remove the addition:
        if (undoRedo.canUndo()) {
//            System.err.println("Addition undone");
            undoRedo.undo();
        }
        // update the line numbers:
        doingUndoRedo = true;
        lineCount = 1;
        updateLineNumbers(textPane.getText(), true);
        doingUndoRedo = false;
    }//GEN-LAST:event_menuEditUndoActionPerformed

    private void radioFontSize10ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioFontSize10ActionPerformed
        // TODO add your handling code here:
        lines.setFont(new java.awt.Font(fontName, 0, 10));
        textPane.setFont(new java.awt.Font(fontName, 0, 10));
        fontSize = 10;
    }//GEN-LAST:event_radioFontSize10ActionPerformed

    private void radioFontSize12ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioFontSize12ActionPerformed
        // TODO add your handling code here:
        lines.setFont(new java.awt.Font(fontName, 0, 12));
        textPane.setFont(new java.awt.Font(fontName, 0, 12));
        fontSize = 12;
    }//GEN-LAST:event_radioFontSize12ActionPerformed

    private void radioFontSize14ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioFontSize14ActionPerformed
        // TODO add your handling code here:
        lines.setFont(new java.awt.Font(fontName, 0, 14));
        textPane.setFont(new java.awt.Font(fontName, 0, 14));
        fontSize = 14;
    }//GEN-LAST:event_radioFontSize14ActionPerformed

    private void radioFontSize16ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioFontSize16ActionPerformed
        // TODO add your handling code here:
        lines.setFont(new java.awt.Font(fontName, 0, 16));
        textPane.setFont(new java.awt.Font(fontName, 0, 16));
        fontSize = 16;
    }//GEN-LAST:event_radioFontSize16ActionPerformed

    private void radioFontSize18ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioFontSize18ActionPerformed
        // TODO add your handling code here:
        lines.setFont(new java.awt.Font(fontName, 0, 18));
        textPane.setFont(new java.awt.Font(fontName, 0, 18));
        fontSize = 18;
    }//GEN-LAST:event_radioFontSize18ActionPerformed

    private void radioTabWidth2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioTabWidth2ActionPerformed
        // TODO add your handling code here:
        tab = "  ";
        tabLen = tab.length();
    }//GEN-LAST:event_radioTabWidth2ActionPerformed

    private void radioTabWidth3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioTabWidth3ActionPerformed
        // TODO add your handling code here:
        tab = "   ";
        tabLen = tab.length();
    }//GEN-LAST:event_radioTabWidth3ActionPerformed

    private void radioTabWidth4ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioTabWidth4ActionPerformed
        // TODO add your handling code here:
        tab = "    ";
        tabLen = tab.length();
    }//GEN-LAST:event_radioTabWidth4ActionPerformed

    private void menuFileSaveAsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuFileSaveAsActionPerformed
        // TODO add your handling code here:
        saveFile(true);
    }//GEN-LAST:event_menuFileSaveAsActionPerformed

    private void menuPreferencesSetPathsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_menuPreferencesSetPathsActionPerformed
        // TODO add your handling code here:
        setPathSPTextField.setText(spLocation);
        setPathPCTextField.setText(praatDir);
        setPathsFrame.setLocationRelativeTo(rootPane);
        setPathsFrame.pack();
        setPathsFrame.setVisible(true);
    }//GEN-LAST:event_menuPreferencesSetPathsActionPerformed

    private void setPathsSPChooseButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_setPathsSPChooseButtonActionPerformed
        // TODO add your handling code here:
        int returnVal = dirSelect.showDialog(this, "OK");
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File dir = dirSelect.getSelectedFile();
            String path = dir.getAbsolutePath();
            setPathSPTextField.setText(path);
        }
    }//GEN-LAST:event_setPathsSPChooseButtonActionPerformed

    private void setPathsConfirmButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_setPathsConfirmButtonActionPerformed
        // TODO add your handling code here:
        spLocation = setPathSPTextField.getText();
        praatDir = setPathPCTextField.getText();
        setPathsFrame.setVisible(false);
    }//GEN-LAST:event_setPathsConfirmButtonActionPerformed

    private void radioEncodingUTF8ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioEncodingUTF8ActionPerformed
        // TODO add your handling code here:
        encoding = "UTF-8";
    }//GEN-LAST:event_radioEncodingUTF8ActionPerformed

    private void radioEncodingCurrLocActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_radioEncodingCurrLocActionPerformed
        // TODO add your handling code here:
        encoding = currentLocale;
    }//GEN-LAST:event_radioEncodingCurrLocActionPerformed

    private void setPathsPCChooseButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_setPathsPCChooseButtonActionPerformed
        // TODO add your handling code here:
        int returnVal = dirSelect.showDialog(this, "OK");
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File dir = dirSelect.getSelectedFile();
            String path = dir.getAbsolutePath();
            setPathPCTextField.setText(path);
        }
    }//GEN-LAST:event_setPathsPCChooseButtonActionPerformed

    public static void searchAndReplaceEscapeListener(final JDialog dialog) {

        ActionListener escListener = new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.setVisible(false);
            }
        };

        dialog.getRootPane().registerKeyboardAction(escListener,
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

    }

    private String treatEscapeSequences(String str) {
        str = str.replaceAll("\\\\n", "\n");
        str = str.replaceAll("\\\\t", "\t");
        str = str.replaceAll("\\\\r", "\r");
        return str;
    }

    private Boolean findNext(String str, int docLen, Boolean cycle) {
        try {
            String text = doc.getText(0, docLen);
            int[] match = doMatch(text, str, searchOffset);
            if (match[0] > -1) {
                doc.setCharacterAttributes(match[0], match[1], searchHighlight, false);
                textPane.setCaretPosition(match[0]);
                // the current searchOffset will serve as replaceOffset for
                // replaceCurrent:
                replaceOffset = searchOffset;
            } else {
                if (cycle) {
//                    JOptionPane.showMessageDialog(searchAndReplaceDialog, "Search hit bottom.\nPress Find next to continue from top.", "No more results", JOptionPane.INFORMATION_MESSAGE);
                    searchOffset = 0;
                    return false;
                } else {
                    searchOffset = textPane.getSelectionStart();
                    return false;
                }
            }
            searchOffset = match[0] + 1 > docLen ? docLen : match[0] + 1;
            return true;
        } catch (BadLocationException e) {
        }
        return false;
    }

    private Boolean replaceNext(String oldStr, String newStr, int docLen, Boolean cycle) {
        try {
            String text = doc.getText(0, docLen);
            int newStrLen = newStr.length();
            int[] match = doMatch(text, oldStr, searchOffset);
            if (match[0] > -1) {
                doc.remove(match[0], match[1]);
                // we cannot set the char attr of the string upon insert, because
                // insertString strips all highlighting of background...
                doc.insertString(match[0], newStr, null);
                // ... so we have to do it post hoc here:
                doc.setCharacterAttributes(match[0], newStrLen, searchHighlight, false);
                textPane.setCaretPosition(match[0]);
                // the current searchOffset will serve as replaceOffset for
                // replaceCurrent:
                replaceOffset = searchOffset;
            } else {
                if (cycle) {
//                    JOptionPane.showMessageDialog(searchAndReplaceDialog, "Search hit bottom.\nPress Find next to continue from top.", "No more results", JOptionPane.INFORMATION_MESSAGE);
                    searchOffset = 0;
                    return false;
                } else {
                    searchOffset = textPane.getSelectionStart();
                    return false;
                }
            }
            int newDocLen = docLen + newStrLen - oldStr.length();
            searchOffset = match[0] + newStrLen > newDocLen ? newDocLen : match[0] + newStrLen;
            return true;
        } catch (BadLocationException e) {
        }
        return false;
    }

    private int[] doMatch(String text, String str, int searchOffset) {
        /*
         * Perform either a normal or a regex matching of the sequence contained
         * in str w.r.t. to text, starting at searchOffset.
         */
        // prepare return array (default is negative):
        int[] match = {-1, -1};
        if (checkBoxUseRegex.isSelected()) {
            Pattern pattern = Pattern.compile(str);
            Matcher matcher = pattern.matcher(text);
            if (matcher.find(searchOffset)) {
                // Let's keep these debug statements here for now, one never knows
                // with regex implementations, so they might come in handy.
//                System.err.println(matcher.group());
//                System.err.println(pattern.toString());
                match[0] = matcher.start();
                match[1] = matcher.group().length();
                return match;
            } else {
                return match;
            }
        } else {
            match[0] = text.indexOf(str, searchOffset);
            match[1] = str.length();
            return match;
        }
    }

    private void prepend(String prepStr) {
        int startSel = textPane.getSelectionStart();
        int endSel = textPane.getSelectionEnd();
        int prepLen = prepStr.length();
        if (startSel == endSel) {
            // comment out single line; in this case, startSel == endSel == caret position
            try {
                String text = textPane.getText(0, startSel);
                int offset = text.lastIndexOf(lineSep);
                doc.insertString(offset + 1, prepStr, null);
            } catch (BadLocationException e) {
            }
        } else {
            // comment out multiple lines (whole selection):
            try {
                // first, find beginning of line where selection starts, and readjust
                // the start of the selection there:
                String preText = textPane.getText(0, startSel);
                startSel = preText.lastIndexOf(lineSep);
                // if selection starts at line 1, the preceding returns -1; set 
                // a backup startSel to 0...:
                int startSel2 = startSel < 0 ? 0 : startSel;
                // ...'cause we need a valid startSel here...:
                String text = textPane.getText(startSel2, endSel - startSel2);
                text = startSel < 0 ? lineSep + text : text;
                int offset = 0;
                int increment = 0;
                while (offset >= 0) {
                    // ... but we'll be needing the potential -1 here!:
                    doc.insertString(startSel + offset + increment + 1, prepStr, null);
                    offset = text.indexOf(lineSep, offset + lineSepLen);
                    // at each loop, we add 2 chars ("# ") to the region of text
                    // we're commenting out, but our variable text doesn't record
                    // this --> need to keep track of it independently:
                    increment += prepLen;
                }
            } catch (BadLocationException e) {
            }
        }
    }

    private void unprepend(String unprepStr) {
        int startSel = textPane.getSelectionStart();
        int endSel = textPane.getSelectionEnd();
        int unprepLen = unprepStr.length();
        if (startSel == endSel) {
            // uncomment single line; in this case, startSel == endSel == caret position
            try {
                String text = textPane.getText(0, startSel);
                int offset = text.lastIndexOf(lineSep);
                if (textPane.getText(offset + 1, unprepLen).equals(unprepStr)) {
                    doc.remove(offset + 1, unprepLen);
                }
            } catch (BadLocationException e) {
            }
        } else {
            // uncomment multiple lines (whole selection):
            try {
                // first, find beginning of line where selection starts, and readjust
                // the start of the selection there:
                String preText = textPane.getText(0, startSel);
                startSel = preText.lastIndexOf(lineSep);
                // if selection starts at line 1, the preceding returns -1; set 
                // a backup startSel to 0...:
                int startSel2 = startSel < 0 ? 0 : startSel;
                // ...'cause we need a valid startSel here...:
                String text = textPane.getText(startSel2, endSel - startSel2);
                text = startSel < 0 ? lineSep + text : text;
                int offset = 0;
                int increment = 0;
                while (offset >= 0) {
                    if (text.substring(offset + 1, offset + unprepLen + 1).equals(unprepStr)) {
                        // ... but we'll be needing the potential -1 here!:
                        doc.remove(startSel + offset + increment + 1, unprepLen);
                        // at each successful loop, we remove 2 chars ("# ") from the region of text
                        // we're uncommenting, but our variable text doesn't record
                        // this --> need to keep track of it independently:
                        increment -= unprepLen;
                    }
                    offset = text.indexOf(lineSep, offset + lineSepLen);
                }
            } catch (BadLocationException e) {
            }
        }
    }

    private void saveFile(Boolean saveAs) {
        if (file != null && !saveAs) {
            try {
                // overwrite backup:
                FileOutputStream streamBak = new FileOutputStream(backup.getAbsoluteFile(), false);
                OutputStreamWriter writeBak = new OutputStreamWriter(streamBak, encoding);
                if (utf8BomFlag) {
                    writeBak.write(UTF8_BOM);
                }
                textPane.write(writeBak);
                writeBak.close();
                streamBak.close();

                // overwrite file:
                FileOutputStream streamFile = new FileOutputStream(file.getAbsoluteFile(), false);
                OutputStreamWriter writeFile = new OutputStreamWriter(streamFile, encoding);
                if (utf8BomFlag) {
                    writeFile.write(UTF8_BOM);
                }
                textPane.write(writeFile);
                writeFile.close();
                streamFile.close();

                documentModified = false;
            } catch (Exception e) {
                JOptionPane.showMessageDialog(rootPane, "File couldn't be written!", "File save error", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            int returnVal = fileOpenSave.showSaveDialog(this);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                file = fileOpenSave.getSelectedFile();
                String filename = String.valueOf(file.getAbsoluteFile());

                backup = new java.io.File(filename + "~");
                try {
                    // overwrite backup:
                    FileOutputStream streamBak = new FileOutputStream(backup.getAbsoluteFile(), false);
                    OutputStreamWriter writeBak = new OutputStreamWriter(streamBak, encoding);
                    if (utf8BomFlag) {
                        writeBak.write(UTF8_BOM);
                    }
                    textPane.write(writeBak);
                    writeBak.close();
                    streamBak.close();

                    // overwrite file:
                    FileOutputStream streamFile = new FileOutputStream(file.getAbsoluteFile(), false);
                    OutputStreamWriter writeFile = new OutputStreamWriter(streamFile, encoding);
                    if (utf8BomFlag) {
                        writeFile.write(UTF8_BOM);
                    }
                    textPane.write(writeFile);
                    writeFile.close();
                    streamFile.close();

                    setTitle(filename + " - PraatEdit");
                    documentModified = false;
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(rootPane, "File couldn't be written!", "File output error", JOptionPane.ERROR_MESSAGE);
                }
            } else {
                System.out.println("File access cancelled by user.");
            }
        }
    }

    private Boolean prepareFileForSendPraat() {
        /*
         * THE FOLLOWING DEALS WITH A QUIRK OF SENDPRAAT:
         * the path to the file might contain spaces, which sendpraat cannot
         * handle; we therefore need to give sendpraat the name of the file
         * without the full path, but in order to find the file in this case,
         * sendpraat needs the file to be located in ~/.praat-dir/; the most
         * hassle-free way to deal with this is just to make a copy there...
         * EDIT: and of course, ~/.praat-dir is ~/Praat under Windows. (sigh)
         */
        File runFile = new File(praatDir + fileSep + "PraatEditMessageToPraat");
        try {
            FileOutputStream streamRun = new FileOutputStream(runFile.getAbsoluteFile(), false);
            OutputStreamWriter writeRun = new OutputStreamWriter(streamRun, "UTF-8");
            textPane.write(writeRun);
            writeRun.close();
            streamRun.close();
            return true;
        } catch (IOException ex) {
            System.err.println("Copy of script for sendpraat couldn't be written"
                    + " to " + praatDir + " .");
            JOptionPane.showMessageDialog(rootPane, "Copy of script for sendpraat couldn't be written"
                    + " to " + praatDir + " .\n\n"
                    + ex,
                    "Error running script", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

//    private void openFile() throws FileNotFoundException, IOException, BadLocationException {
//        int returnVal = fileOpen.showOpenDialog(this);
//        // offset from beginning of document (used for displaying the text line by line):
////        int offset = 0;
////        String line;
//        if (returnVal == JFileChooser.APPROVE_OPTION) {
//            java.awt.EventQueue.invokeLater(new Runnable() {
//                @Override
//                public void run() {
//                    file = fileOpen.getSelectedFile();
//                    String filename = String.valueOf(file.getAbsoluteFile());
//                    backup = new java.io.File(filename + "~");
//                    textPane.setText("");
//                    try {
//                        // create a new document and insert into it...
//                        doc = new PraatDocument();
//                        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file.getAbsolutePath())));
//                        loadingProgressFrame.setLocationRelativeTo(rootPane);
//                        loadingProgressFrame.setVisible(true);
////                        LineNumberReader lnr = new LineNumberReader(new FileReader(file));
////                        lnr.skip(Long.MAX_VALUE);
////                        loadingProgressBar.setMaximum(lnr.getLineNumber() + 1);
////                        loadingProgressBar.setMaximum((int) 100);
////                        loadingProgressBar.setValue((int) 50);
//                        int offset = 0;
//                        String line;
////                        int trackLoadProgress = 0;
//                        while ((line = br.readLine()) != null) {
//                            doc.insertString(offset, line + lineSep, null);
//                            offset += line.length() + lineSepLen;
////                            ++trackLoadProgress;
//                        }
//                        br.close();
//                        // ... and only then attach it to the textPane (might speed up loading,
//                        // textPane itself is slow):
//                        textPane.setDocument(doc);
////                        textPane.updateUI();
//                        loadingProgressFrame.setVisible(false);
//                        setTitle(filename + " - PraatEdit");
//                        documentModified = false;
//                    } catch (Exception e) {
//                    }
//                }
//            });
//        } else {
//            System.err.println("File access cancelled by user.");
//        }
//    }
//    private void loadFileIntoTextPane(BufferedReader br, int offset) throws IOException, BadLocationException {
//        String line;
//        while ((line = br.readLine()) != null) {
//            doc.insertString(offset, line + lineSep, null);
//            offset += line.length() + 1;
//        }
//    }
    private void openFile() {
//        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        // don't call the following two methods here, or the caret gets stuck
        // until PraatEdit loses and regains focus - probably some jam in the GUI
        // event queue:
//        loadingProgressFrame.setLocationRelativeTo(rootPane);
//        loadingProgressFrame.setVisible(true);
        openTask = new OpenFileTask();
        openTask.addPropertyChangeListener(this);
        openTask.execute();
    }

    /**
     * Invoked when OpenFileTask's progress property changes.
     */
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (evt.getPropertyName().equals("progress")) {
//            int progress = (Integer) evt.getNewValue();
//            loadingProgressBar.setValue(progress);
            loadingProgressBar.setIndeterminate(true);
        }
    }

    private void runFile() {
        Boolean messageFileWritten = prepareFileForSendPraat();
//        try {
//            Thread.sleep(100);
//        } catch (InterruptedException e) {
//            System.err.println(e);
//        }
        if (messageFileWritten) {
            System.err.println("Sending script to Praat via " + spLocation + fileSep
                    + "sendpraat");
            try {
                String[] cmd = new String[3];
                cmd[0] = spLocation + fileSep + "sendpraat";
                cmd[1] = "praat";
                cmd[2] = "execute " + "PraatEditMessageToPraat";
                Runtime rt = Runtime.getRuntime();
//            System.err.println(Arrays.toString(cmd));
//            System.err.println(file.getParentFile().toString());
                Process p = rt.exec(cmd); //, null, file.getParentFile());
            } catch (IOException e) {
                JOptionPane.showMessageDialog(rootPane, "Error running script! Are "
                        + "you sure you have sendpraat in the directory specified under \n"
                        + "Preferences->Set paths...? If not, please move "
                        + "sendpraat there, or change the path.\n\n"
                        + e,
                        "Error running script", JOptionPane.ERROR_MESSAGE);
                System.err.println(e);
            }
        }
    }

    private void exit() {
        // remove the PraatEditMessageToPraatFile:
        File runFile = new File(userHome + fileSep + praatDir
                + fileSep + "PraatEditMessageToPraat");
        runFile.delete();
        savePreferences();
        setVisible(false);
        dispose();
        System.exit(0);
    }

    private void updateLineNumbers(String str, Boolean insert) {
//        System.err.println("Updating line numbers. Starting line count: "
//                + Integer.toString(lineCount));
        Matcher newlineMatcher = lineSepPattern.matcher(str);
        int dLines = 0;
        while (newlineMatcher.find()) {
            dLines++;
        }
        // if dLines is equal to zero, let's exit early and save some work; but
        // if we're opening a file, it might consist of a single line, so that 
        // dLines will be equal to zero, and in this case, we don't want to
        // return just yet (same goes for doing undo/redo... boy this is getting
        // ugly):
        if (dLines == 0 && !openingFile && !doingUndoRedo) {
            return;
        }
//        System.err.println(dLines);
        // figure out if lines were inserted or deleted, and update the lineCount:
        lineCount += insert ? dLines : -dLines;
        // perhaps there's a better way to update the lineNumbers? keep an array
        // and update it, then stringify it, instead of generating the lineNumbers
        // string anew each time?
        lineNumbers = "";
        for (int i = 1; i < lineCount; i++) {
            lineNumbers += i + lineSep;
        }
        lineNumbers += lineCount;
        lines.setText(lineNumbers);
        // THIS IS EXTREMELY IMPORTANT and must be included in all update
        // methods, otherwise the linenumbers scroll because of the update
        // and become desynchronized with the text:
        lines.updateUI();
    }

    public void mainWindowListener() {
        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (documentModified) {
                    Object[] options = {"Yes", "No", "Cancel"};
                    int answer = JOptionPane.showOptionDialog(rootPane,
                            "File has been modified.\nSave before exiting?",
                            "Save before exiting?",
                            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                            null, options, options[2]);
                    switch (answer) {
                        case 0:
                            saveFile(false);
                            exit();
                            break;
                        case 1:
                            exit();
                    }
                } else {
                    exit();
                }
            }
        });
    }

    private void loadPreferences() {
        System.err.println("Using " + praatEditRCStr + " as preferences file.");
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(praatEditRC.getAbsolutePath())));
//            while ((line = br.readLine()) != null) {
//                System.err.println(line);
//            }
            // throw away first line of rc file (just a comment):
            br.readLine();
            // read in and set font name:
            fontName = br.readLine().split(" ", 2)[1];
            // read in and set font size preferences:
            fontSize = Integer.parseInt(br.readLine().split(" ")[1]);
            switch (fontSize) {
                case 10:
                    radioFontSize10.setSelected(true);
                    break;
                case 12:
                    radioFontSize12.setSelected(true);
                    break;
                case 14:
                    radioFontSize14.setSelected(true);
                    break;
                case 16:
                    radioFontSize16.setSelected(true);
                    break;
                case 18:
                    radioFontSize18.setSelected(true);
                default:
                    radioFontSize14.setSelected(true);
                    fontSize = 14;
            }
            lines.setFont(new java.awt.Font(fontName, 0, fontSize));
            textPane.setFont(new java.awt.Font(fontName, 0, fontSize));
            // read in and set tab-width preferences:
            tabLen = Integer.parseInt(br.readLine().split(" ")[1]);
            switch (tabLen) {
                case 2:
                    radioTabWidth2.setSelected(true);
                    break;
                case 3:
                    radioTabWidth3.setSelected(true);
                    break;
                case 4:
                    radioTabWidth4.setSelected(true);
                    break;
                default:
                    radioTabWidth4.setSelected(true);
                    tabLen = 4;
            }
            tab = tab.substring(0, tabLen);
            // read in and set auto-indenting preferences:
            checkBoxAutoIndent.setSelected(Boolean.parseBoolean(br.readLine().split(" ")[1]));
            // read in and set sendpraat path:
            spLocation = br.readLine().split(" ", 2)[1];
            // read in and set path to Praat config directory:
            praatDir = br.readLine().split(" ", 2)[1];
//            System.err.println(spLocation);
            // read in and set encoding settings
            encoding = br.readLine().split(" ", 2)[1];
            // read in and set last fileChoose directory:
            lastFileChooseDir = br.readLine().split(" ", 2)[1];
            if (encoding.equals("UTF-8")) {
                radioEncodingUTF8.setSelected(true);
            } else {
                radioEncodingCurrLoc.setSelected(true);
            }
            br.close();
        } catch (Exception e) {
            if (e instanceof IOException) {
                System.err.println(e);
                JOptionPane.showMessageDialog(rootPane, "The .praatrc preferences file "
                        + "in\nyour home directory couldn't be read.",
                        "Inaccessible preferences file", JOptionPane.ERROR_MESSAGE);
            } else {
                System.err.println(e);
                // reset all preferences to defaults (we want to save a fresh and
                // correct .praateditrc on exit):
                fontName = "Courier New";
                fontSize = 14;
                tabLen = 4;
                tab = "    ";
                checkBoxAutoIndent.setSelected(true);
                spLocation = userHome;
                praatDir = userHome + fileSep
                        + (osIsWindows ? "Praat" : ".praat-dir");
                encoding = "UTF-8";
                lastFileChooseDir = userHome;
                JOptionPane.showMessageDialog(rootPane, "The .praatrc preferences file "
                        + "in your home directory\nis incompatible with this version "
                        + "of PraatEdit. It will be reset.\nPlease restart PraatEdit.",
                        "Invalid preferences file", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void savePreferences() {
        String sep = System.getProperty("line.separator");
        String prefs = "# Preferences file written by PraatEdit; DO NOT MODIFY!"
                + sep + "FontName " + fontName
                + sep + "FontSize " + Integer.toString(fontSize)
                + sep + "TabWidth " + Integer.toString(tabLen)
                + sep + "AutoIndent " + Boolean.toString(checkBoxAutoIndent.isSelected())
                + sep + "SendpraatDir " + spLocation
                + sep + "PraatConfigDir " + praatDir
                + sep + "ReadWriteEnc " + encoding
                + sep + "LastFileChooseDir " + fileOpenSave.getCurrentDirectory();
//        System.err.println(praatEditRC.getAbsoluteFile().toString());
//        System.err.println(prefs);
        try {
            FileWriter fw = new FileWriter(praatEditRC.getAbsoluteFile(), false);
            BufferedWriter out = new BufferedWriter(fw);
            out.write(prefs);
            out.flush();
            out.close();
            fw.close();
        } catch (IOException e) {
            System.err.println("Couldn't write preferences to file. Insufficient privileges?");
        }
//        UndoableEdit[] arr = new UndoableEdit[4];
    }

    /*
     * A fixed size stack class, for limited undo/redo. (was
     * only useful for attempt at smart undo/redo, which probably
     * can't work; delete later):
     */
//    protected class FixedStack<E> extends Stack<E> {
//        
//        private int maxSize;
//        
//        public FixedStack(int maxSize) {
//            this.maxSize = maxSize;
//        }
//        
//        public void fixedPush(E item) {
//            super.push(item);
//            if (this.size() > this.maxSize) {
//                this.removeElement(this.lastElement());
//            }
//        }
//        
//        public E fixedPop() {
//            if (!this.isEmpty()) {
//                E item = super.pop();
//                return item;
//            } else {
//                // this is ugly and non-general, but what the heck...
//                throw new CannotUndoException();
//            }
//        }
//    }

    /* 
     * wrapper class for UndoManager to make undoTo (protected) accessible (was
     * only useful for attempt at smart undo/redo with FixedStack, which probably
     * can't work; delete later):
     */
//    protected class PraatUndoManager extends UndoManager {
//        public void praatUndoTo(UndoableEdit e) throws CannotUndoException {
//            super.undoTo(e);
//        }
//        
//        public void praatRedoTo(UndoableEdit e) throws CannotUndoException {
//            super.redoTo(e);
//        }
//    }
    protected class PraatUndoRedo implements UndoableEditListener {

        @Override
        public void undoableEditHappened(UndoableEditEvent e) {
            // add edit to manager, update the menus:
//            System.err.println(e.getEdit().toString());
            UndoableEdit edit = e.getEdit();
            // broken code trying to implement smarter undo/redo:
//            if (edit instanceof DefaultStyledDocument.DefaultDocumentEvent) {
//                try {
//                    DefaultStyledDocument.DefaultDocumentEvent event = (DefaultStyledDocument.DefaultDocumentEvent) edit;
//                    String editType = event.getPresentationName();
//                    if (editType.equals("addition")) {
//                        // use these two later to make smarter undo/redo:
//                        int start = event.getOffset();
//                        int len = event.getLength();
////                    System.err.println(event.getPresentationName());
//                        String str = event.getDocument().getText(start, len);
////                        System.err.println(str);
//                        if (str.matches("\\s")) {
//                            undoFixedStack.push(edit);
//                        }
//                    } else if (editType.equals("deletion")) {
//                        undoFixedStack.push(edit);
//                    }
//                    // the following is verbatim from Oracle, my case is different - if
//                    // I want to update the menus, I'll have to change this:
////            undoAction.updateUndoState();
////            redoAction.updateRedoState();
//                } catch (BadLocationException ex) {
//                }
//            }
            undoRedo.addEdit(edit);
        }
    }

    protected class PraatDocument extends DefaultStyledDocument {

        @Override
        public void insertString(int offset, String str, AttributeSet a) throws BadLocationException {
//            System.err.println(str.length());
//            System.err.println(str.codePointAt(0));
//            System.err.println(Arrays.toString(str.toCharArray()));

            // replace tabs with spaces:
            str = (str.matches("\\t")) ? tab : str;
            // testing auto-indent for windows:
//            str = (str.matches("\\n")) ? "\r\n" : str;
            super.insertString(offset, str, a);

            // we don't want the highlighting et al. to figure in the undo/redo list -->
            // deregesiter the listener:
//            doc.removeUndoableEditListener(unEdList);

            // ensure that new strings are always inserted non-highlighted:
            doc.setCharacterAttributes(offset, str.length(), searchNoHighlight, false);
            if (!openingFile) {
                updateLineNumbers(str, true);
            }
            String line = format.highlightCurrentLine(this, str, offset);
            // auto-indenting (really crummy, but it mostly works, so let's leave 
            // it for now); remember that when a newline is inserted, the variable
            // line corresponds to the preceding line (i.e. the on we want in order
            // to determine the indent):
            if (checkBoxAutoIndent.isSelected() && !openingFile) {
                if (line.matches(praatAutoIndent) && str.matches(lineSepRegex)) {
                    Matcher spaceMatcher = praatSpacePattern.matcher(line);
                    spaceMatcher.find();
                    String leadingSpace = spaceMatcher.group(1);
                    // remove the initial newline:
                    leadingSpace = leadingSpace.replaceAll(lineSep, "");
                    super.insertString(offset + lineSepLen, leadingSpace + tab, a);
                } else if (str.matches(lineSepRegex)) {
                    Matcher spaceMatcher = praatSpacePattern.matcher(line);
                    spaceMatcher.find();
                    String leadingSpace = spaceMatcher.group(1);
                    // remove the initial newline:
                    leadingSpace = leadingSpace.replaceAll(lineSep, "");
                    super.insertString(offset + lineSepLen, leadingSpace, a);
                } else if (line.matches(praatAutoDeindent)) {
                    // keyword which triggered de-indentation:
                    Matcher triggerMatcher = praatAutoDeindentPattern.matcher(line);
                    triggerMatcher.find();
                    String trigger = triggerMatcher.group(1);
                    int triggerLen = trigger.length();
                    // only de-indent if the trigger keyword has just been added!
                    if (this.getText(offset - triggerLen + 1, triggerLen).equals(trigger)) {
                        // first need to check how much we can remove (only up to the
                        // preceding newline and no further):
                        String potentialRemove = this.getText(offset - triggerLen - tabLen + 1, tabLen);
                        if (!potentialRemove.contains(lineSep)) {
                            super.remove(offset - triggerLen - tabLen + 1, tabLen);
                        }
                    }
                }
            }
            documentModified = true;
            // re-register the edit listener now that we're done:
//            doc.addUndoableEditListener(unEdList);
        }

        @Override
        public void remove(int offset, int len) throws BadLocationException {
            String str = this.getText(offset, len);
            super.remove(offset, len);
            // we don't want the highlighting et al. to figure in the undo/redo list -->
            // deregesiter the listener:
//            doc.removeUndoableEditListener(unEdList);
            if (!openingFile) {
                updateLineNumbers(str, false);
            }
            format.highlightCurrentLine(this, str, offset);
            documentModified = true;
            // re-register the edit listener now that we're done:
//            doc.addUndoableEditListener(unEdList);
        }
    };

    protected class OpenFileTask extends SwingWorker<Void, Void> {

        /*
         * Load and parse file. Executed in background thread.
         */
        @Override
        public Void doInBackground() {
            // set up the flag that tells the insert/remove methods of PraatDocument
            // not to update line numbers (it doesn't make sense to do it on each edit):
            openingFile = true;
            int returnVal = fileOpenSave.showOpenDialog(rootPane);
            loadingProgressFrame.setLocationRelativeTo(rootPane);
            loadingProgressFrame.setVisible(true);
            // offset from beginning of document (used for displaying the text line by line):
            int offset = 0;
//            int trackLoadProgress = 0;
            String line;
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                file = fileOpenSave.getSelectedFile();
                filename = String.valueOf(file.getAbsoluteFile());
                backup = new java.io.File(filename + "~");
                try {
                    // doesn't work, the maximum of the progress bar isn't updated
                    // in time:
//                    // count number of lines in file:
//                    Scanner countNumOfLinesInFile = new Scanner(file);
//                    int numOfLinesInFile = 0;
//                    while (countNumOfLinesInFile.hasNextLine()) {
//                        countNumOfLinesInFile.nextLine();
//                        numOfLinesInFile++;
//                    }
//                    countNumOfLinesInFile.close();
//                    loadingProgressBar.setMaximum(numOfLinesInFile);
                    // can't do this either, document then fails to load (thread
                    // issues again, I guess...):
//                    // unhook the document from the textpane
//                    textPane.setDocument(null);
                    // create a new document and insert into it...
                    doc = new PraatDocument();
//                    Scanner readFileIn = new Scanner(file);
                    BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file.getAbsolutePath()), encoding));
//                        LineNumberReader lnr = new LineNumberReader(new FileReader(file));
//                        lnr.skip(Long.MAX_VALUE);
//                        loadingProgressBar.setMaximum(lnr.getLineNumber() + 1);
                    setProgress(1);
                    utf8BomFlag = false;
                    int i = 0;
                    while ((line = br.readLine()) != null) {
//                    while (readFileIn.hasNextLine()) {
//                        line  = readFileIn.nextLine();
                        // throw away UTF-8 BOM if present:
                        if (i == 0 && line.startsWith(UTF8_BOM)) {
                            line = line.substring(1);
                            utf8BomFlag = true;
                        }
                        doc.insertString(offset, line + lineSep, null);
                        offset += line.length() + lineSepLen;
                        i++;
//                        setProgress(++trackLoadProgress);
                    }
                    // remove the last line separator (bc it wasn't in the original
                    // file);
                    doc.remove(offset - 1, 1);
                    br.close();
//                    readFileIn.close();
                    // reset the line numbers (they'll be updated separately by
                    // the wrap-up method done()):
                    lineCount = 1;
                    documentModified = false;
                    // sleep for a while (if the file is loaded too quickly,
                    // the loading progress bar is not dismissed):
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                    }
                } catch (Exception e) {
                }
            } else {
                // sleep for a while (if the file is loaded too quickly,
                // the loading progress bar is not dismissed):
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
                // reset the line numbers even though we haven't opened a new file,
                // because they'll still be updated separately by the wrap-up method done()):
                lineCount = 1;
                System.err.println("File access cancelled by user.");
            }
            return null;
        }

        /*
         * Executed in event dispatching thread
         */
        @Override
        public void done() {
            textPane.setDocument(doc);
            doc.addUndoableEditListener(unEdList);
            // TODO: count the linenumbers while loading the file and implement
            // a quicker method w/o regex matching for updating them upon loading
            // a file:
            updateLineNumbers(textPane.getText(), true);
            textPane.updateUI();
            // reset the title of the frame, but only if a file has actually been
            // selected:you
            if (filename != null) {
                setTitle(filename + " - PraatEdit");
            }
            loadingProgressFrame.setVisible(false);
            openingFile = false;
        }
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;


                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(praatEdit.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(praatEdit.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(praatEdit.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(praatEdit.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new praatEdit().setVisible(true);
            }
        });
    }
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JCheckBoxMenuItem checkBoxAutoIndent;
    private javax.swing.JCheckBox checkBoxUseRegex;
    private javax.swing.JFileChooser dirSelect;
    private javax.swing.JFileChooser fileOpenSave;
    private javax.swing.JButton findAllButton;
    private javax.swing.JButton findNext;
    private javax.swing.JTextField findTextField;
    private javax.swing.ButtonGroup fontSizeButtonGroup;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JPopupMenu.Separator jSeparator1;
    private javax.swing.JPopupMenu.Separator jSeparator2;
    private javax.swing.JPopupMenu.Separator jSeparator3;
    private javax.swing.JPopupMenu.Separator jSeparator4;
    private javax.swing.JProgressBar loadingProgressBar;
    private javax.swing.JDialog loadingProgressFrame;
    private javax.swing.JMenuBar menuBar;
    private javax.swing.JMenu menuEdit;
    private javax.swing.JMenuItem menuEditComment;
    private javax.swing.JMenuItem menuEditFind;
    private javax.swing.JMenuItem menuEditIndent;
    private javax.swing.JMenuItem menuEditRedo;
    private javax.swing.JMenuItem menuEditUncomment;
    private javax.swing.JMenuItem menuEditUndo;
    private javax.swing.JMenuItem menuEditUnhighlight;
    private javax.swing.JMenuItem menuEditUnindent;
    private javax.swing.JMenu menuFile;
    private javax.swing.JMenuItem menuFileNew;
    private javax.swing.JMenuItem menuFileOpen;
    private javax.swing.JMenuItem menuFileRun;
    private javax.swing.JMenuItem menuFileSave;
    private javax.swing.JMenuItem menuFileSaveAs;
    private javax.swing.JMenuItem menuFileSaveNew;
    private javax.swing.JMenuItem menuFileSaveOpen;
    private javax.swing.JMenuItem menuFileSaveRun;
    private javax.swing.JMenu menuPreferences;
    private javax.swing.JMenu menuPreferencesFontSize;
    private javax.swing.JMenuItem menuPreferencesSetPaths;
    private javax.swing.JMenu menuPreferencesTabWidth;
    private javax.swing.JPanel noWrapPanel;
    private javax.swing.JRadioButtonMenuItem radioEncodingCurrLoc;
    private javax.swing.JRadioButtonMenuItem radioEncodingUTF8;
    private javax.swing.JRadioButtonMenuItem radioFontSize10;
    private javax.swing.JRadioButtonMenuItem radioFontSize12;
    private javax.swing.JRadioButtonMenuItem radioFontSize14;
    private javax.swing.JRadioButtonMenuItem radioFontSize16;
    private javax.swing.JRadioButtonMenuItem radioFontSize18;
    private javax.swing.JRadioButtonMenuItem radioTabWidth2;
    private javax.swing.JRadioButtonMenuItem radioTabWidth3;
    private javax.swing.JRadioButtonMenuItem radioTabWidth4;
    private javax.swing.ButtonGroup readWriteEncButtonGroup;
    private javax.swing.JMenu readWriteEncoding;
    private javax.swing.JButton replaceAllButton;
    private javax.swing.JButton replaceCurrentButton;
    private javax.swing.JTextField replaceTextField;
    private javax.swing.JScrollPane scrollPane;
    private javax.swing.JDialog searchAndReplaceDialog;
    private javax.swing.JTextField setPathPCTextField;
    private javax.swing.JTextField setPathSPTextField;
    private javax.swing.JButton setPathsConfirmButton;
    private javax.swing.JDialog setPathsFrame;
    private javax.swing.JButton setPathsPCChooseButton;
    private javax.swing.JButton setPathsSPChooseButton;
    private javax.swing.ButtonGroup tabWidthButtonGroup;
    private javax.swing.JTextPane textPane;
    // End of variables declaration//GEN-END:variables
}
