import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import org.bouncycastle.util.encoders.Base64
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JPanel


class DisplayToolWindow : ToolWindowFactory, DumbAware {
    private val PORT_VARIABLE = "sys.modules['pydev_publisher'].port"

    private lateinit var myToolWindow: ToolWindow
    private lateinit var myProject: Project
    private var myConsoleTitle = "Python Console"
    private var myMainPanel: JPanel? = null
    private var myStatusBar: JLabel? = null
    private var mySubscribers = ArrayList<Subscriber>()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        myToolWindow = toolWindow
        myProject = project
        val content = createContent()
        toolWindow.contentManager.addContent(content)
    }

    fun addItem(imgStr: String) {
        var img: BufferedImage? = null
        try {
            img = ImageIO.read(ByteArrayInputStream(Base64.decode(imgStr)))
        } catch (e: Exception) {}
        if (img != null) {
            addItem(img)
        }
    }

    fun addItem(img: BufferedImage) {
        myMainPanel?.add(JLabel(ImageIcon(img)), 0)
        myMainPanel?.revalidate()
    }

    fun connect() {
        myStatusBar?.text = "querying port..."
        val portVar = PythonConsoleUtils.getVariable(myProject, PORT_VARIABLE)
        val port = portVar.value?.toIntOrNull()
        if (portVar.type != "int" || port == null) {
            myStatusBar?.text = "query port failed"
            return
        }
        myStatusBar?.text = "connecting to $port..."
        mySubscribers.add(Subscriber(port))
        myStatusBar?.text = "connected to $port"
    }

    private fun createActionBar(panel : JPanel) {
        val toolbarActions = DefaultActionGroup()
        val actionToolbar = ActionManager.getInstance().createActionToolbar("PydevDisplay", toolbarActions, true)

        panel.add(actionToolbar.component, BorderLayout.NORTH)
        actionToolbar.setTargetComponent(panel)

        toolbarActions.add(ConnectAction())
    }

    fun createContent() : Content {
        val panel = SimpleToolWindowPanel(false, true)

        createActionBar(panel)

        val content = ContentFactory.SERVICE.getInstance().createContent(panel, myConsoleTitle, /*isLockable: */ false)
        content.isCloseable = true
        val statusBar = JLabel("disconnected")
        panel.add(statusBar, BorderLayout.SOUTH)
        myStatusBar = statusBar

        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
        }
        myMainPanel = mainPanel
        val scrollPane = JBScrollPane(mainPanel).apply {
            isVisible = true
        }
        panel.add(scrollPane)

        return content
    }

    inner class Subscriber(port: Int) {
        val myPort = port
        val myThread: Thread
        var mySocket: ZMQ.Socket? = null
        init {
            myThread = Thread(::connectToPublisher)
            myThread.start()
        }

        fun connectToPublisher() {
            ZContext().use { context ->
                // Socket to talk to clients
                val socket = context.createSocket(ZMQ.SUB)
                socket.connect("tcp://localhost:$myPort")
                socket.subscribe("")
                mySocket = socket

                while (!Thread.currentThread().isInterrupted) {
                    // Block until a message is received
                    val reply = socket.recvStr(0)
                    val data = parse(reply)
                    if (data?.containsKey("image/png") == true) {
                        addItem(data["image/png"]!!)
                    }
                }
            }
        }

        private fun parse(resp: String) : HashMap<String, String>? {
            val jsonParser = JsonParser()
            val json = jsonParser.parse(resp)
            try {
                val data = HashMap<String, String>()
                val jsonData = json.asJsonObject["data"].asJsonObject
                jsonData.keySet().forEach {
                    data[it] = jsonData[it].asString
                }
                return data
            } catch (ignored: IllegalStateException) {}
            return null
        }
    }

    inner class ConnectAction : AnAction(AllIcons.Plugins.Downloads) {
        override fun actionPerformed(event: AnActionEvent) {
            connect()
        }
    }
}
