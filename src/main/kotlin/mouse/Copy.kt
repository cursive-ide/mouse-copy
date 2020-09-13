package mouse

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.TextRange
import com.intellij.util.ui.UIUtil
import java.awt.AWTEvent
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * @author Colin Fleming
 */

class Copy : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val event = e.inputEvent
    val editor = e.getData(CommonDataKeys.EDITOR) as? EditorEx ?: return
    val project = editor.project ?: return

    // This is the currently selected editor. This may be different to the one for the action,
    // allowing us to copy from one tab to another.
    val selectedEditor = FileEditorManager.getInstance(project).selectedTextEditor ?: return

    if (event is MouseEvent) {
      val area = editor.getMouseEventArea(event)
      // I'm guessing here, assuming that we only want events in the editing area?
      if (area !== EditorMouseEventArea.EDITING_AREA) return

      val source = event.source as? Component ?: return
      val point = SwingUtilities.convertPoint(source, event.point, editor.contentComponent)
      val logicalPosition = editor.xyToLogicalPosition(point)

      // This is what we are looking for, offset is the 0-based char offset in the document
      val offset = editor.logicalPositionToOffset(logicalPosition)

      val charSequence = editor.document.immutableCharSequence

      // Try to find matching braces at this offset, and bail if we can't
      val bracedFormRange = findBracedForm(editor, charSequence, offset) ?: return

      // If we did, get the text from the document using the returned range
      val bracedFormText = charSequence.substring(bracedFormRange.startOffset, bracedFormRange.endOffset)

      // Insert it into the currently selected editor
      WriteCommandAction.runWriteCommandAction(project) {
        EditorModificationUtil.insertStringAtCaret(selectedEditor, bracedFormText)
      }
    }
  }

  fun findBracedForm(editor: EditorEx, charSequence: CharSequence, offset: Int): TextRange? {
    // Brace matching in IntelliJ is done at the lexer token level, so let's get an iterator for those
    val iterator = editor.highlighter.createIterator(offset)
    val fileType = editor.virtualFile.fileType

    if (!BraceMatchingUtil.findStructuralLeftBrace(fileType, iterator, charSequence)) return null
    val start = iterator.start
    if (!BraceMatchingUtil.matchBrace(charSequence, fileType, iterator, true)) return null
    return TextRange(start, iterator.end)
  }

  override fun update(e: AnActionEvent) {
    val event = e.inputEvent
    val editor = e.getData(CommonDataKeys.EDITOR)
    val project = e.getData(CommonDataKeys.PROJECT)
    val isEnabled = event is MouseEvent &&
        editor != null &&
        project != null &&
        editor.getMouseEventArea(event) === EditorMouseEventArea.EDITING_AREA
    val presentation = e.presentation
    presentation.isEnabled = isEnabled
    presentation.isVisible = isEnabled
  }
}

class CopyStartupActivity : StartupActivity {
  // This is all very low-level, and is required to get the mouse events before
  // the editor has a chance to capture them e.g. to move the caret on a click.
  private val presentationFactory = PresentationFactory()

  // Does this event match a shortcut assigned to our action?
  fun matches(shortcut: Shortcut, event: MouseEvent): Boolean =
    shortcut is MouseShortcut &&
        shortcut.button == MouseShortcut.getButton(event) &&
        shortcut.clickCount == event.clickCount &&
        (shortcut.modifiers and (InputEvent.SHIFT_DOWN_MASK
            or InputEvent.ALT_DOWN_MASK
            or InputEvent.ALT_GRAPH_DOWN_MASK
            or InputEvent.CTRL_DOWN_MASK
            or InputEvent.META_DOWN_MASK)) ==
        (event.modifiersEx and (InputEvent.SHIFT_DOWN_MASK
            or InputEvent.ALT_DOWN_MASK
            or InputEvent.ALT_GRAPH_DOWN_MASK
            or InputEvent.CTRL_DOWN_MASK
            or InputEvent.META_DOWN_MASK))

  fun dispatcher(event: AWTEvent): Boolean {
    // We need to capture all three of these events if the modifiers match the
    // action, to stop the IDE seeing partial events.
    if (event is MouseEvent && (event.id == MouseEvent.MOUSE_PRESSED ||
          event.id == MouseEvent.MOUSE_RELEASED ||
          event.id == MouseEvent.MOUSE_CLICKED)) {
      val keymapManager = KeymapManager.getInstance()
      val keymap = keymapManager.activeKeymap
      val shortcuts = keymap.getShortcuts("MouseCopy")

      // Check whether this mouse event corresponds to a shortcut assigned to our action
      if (shortcuts.find { matches(it, event) } == null) return false

      // We'll only act on mouse released, but will capture the rest
      if (event.id != MouseEvent.MOUSE_RELEASED) {
        event.consume()
        return true
      }

      val actionManager = ActionManagerEx.getInstanceEx()
      val action = actionManager.getAction("MouseCopy") ?: return false
      val component = UIUtil.getDeepestComponentAt(event.component, event.x, event.y) as? JComponent ?: return false
      val dataContext = DataManager.getInstance().getDataContext(component)
      val modifiers = event.modifiers
      val presentation = presentationFactory.getPresentation(action)
      val actionEvent = AnActionEvent(
        event,
        dataContext,
        ActionPlaces.MOUSE_SHORTCUT,
        presentation,
        actionManager,
        modifiers
      )
      // Copied from com.intellij.openapi.keymap.impl.IdeMouseEventDispatcher
      if (ActionUtil.lastUpdateAndCheckDumb(action, actionEvent, false)) {
        actionManager.fireBeforeActionPerformed(action, dataContext, actionEvent)
        val context = PlatformDataKeys.CONTEXT_COMPONENT.getData(dataContext)
        if (context != null && !context.isShowing) return false
        ActionUtil.performActionDumbAware(action, actionEvent)
        actionManager.fireAfterActionPerformed(action, dataContext, actionEvent)
        event.consume()
        return true
      }
    }
    return false
  }

  override fun runActivity(project: Project) {
    IdeEventQueue.getInstance().addDispatcher(
      ::dispatcher, DisposableService.getInstance(project)
    )
  }
}

class DisposableService : Disposable {
  override fun dispose() {
  }

  companion object {
    fun getInstance(project: Project) = ServiceManager.getService(project, DisposableService::class.java)
  }
}