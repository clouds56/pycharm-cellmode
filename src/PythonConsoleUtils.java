import com.google.common.collect.Lists;
import com.intellij.execution.ExecutionHelper;
import com.intellij.execution.console.LanguageConsoleView;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.util.Consumer;
import com.intellij.util.NotNullFunction;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.jetbrains.python.console.*;
import com.jetbrains.python.console.pydev.PydevCompletionVariant;
import com.jetbrains.python.debugger.PyDebugValue;
import com.jetbrains.python.debugger.PyDebuggerException;
import com.jetbrains.python.debugger.PyFrameAccessor;
import com.jetbrains.python.debugger.pydev.GetVariableCommand;
import com.jetbrains.python.debugger.pydev.PyDebugCallback;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * Collection of utilities to interact with the internal python console.
 * Mostly copied from PyExecuteSelectionAction from pycharm's codebase
 */
public class PythonConsoleUtils {
    public static void execute(final AnActionEvent e, final String selectionText) {
        final Editor editor = CommonDataKeys.EDITOR.getData(e.getDataContext());
        Project project = CommonDataKeys.PROJECT.getData(e.getDataContext());
        Module module = e.getData(LangDataKeys.MODULE);

        findCodeExecutor(e, new Consumer<PyCodeExecutor>() {
            @Override
            public void consume(PyCodeExecutor codeExecutor) {
                executeInConsole(codeExecutor, selectionText, editor);
            }
        }, editor, project, module);
    }

    private static void selectConsole(@NotNull DataContext dataContext, @NotNull Project project,
                                      final Consumer<PyCodeExecutor> consumer) {
        Collection<RunContentDescriptor> consoles = getConsoles(project);

        ExecutionHelper
                .selectContentDescriptor(dataContext, project, consoles, "Select console to execute in", new Consumer<RunContentDescriptor>() {
                    @Override
                    public void consume(RunContentDescriptor descriptor) {
                        if (descriptor != null && descriptor.getExecutionConsole() instanceof PyCodeExecutor) {
                            consumer.consume((PyCodeExecutor) descriptor.getExecutionConsole());
                        }
                    }
                });
    }

    private static Collection<RunContentDescriptor> getConsoles(Project project) {
        PythonConsoleToolWindow toolWindow = PythonConsoleToolWindow.getInstance(project);

        if (toolWindow != null && toolWindow.getToolWindow().isVisible()) {
            RunContentDescriptor selectedContentDescriptor = toolWindow.getSelectedContentDescriptor();
            return selectedContentDescriptor != null ? Lists.newArrayList(selectedContentDescriptor) : Lists.<RunContentDescriptor>newArrayList();
        }

        Collection<RunContentDescriptor> descriptors =
                ExecutionHelper.findRunningConsole(project, new NotNullFunction<RunContentDescriptor, Boolean>() {
                    @NotNull
                    @Override
                    public Boolean fun(RunContentDescriptor dom) {
                        return dom.getExecutionConsole() instanceof PyCodeExecutor && isAlive(dom);
                    }
                });

        if (descriptors.isEmpty() && toolWindow != null) {
            return toolWindow.getConsoleContentDescriptors();
        }
        else {
            return descriptors;
        }
    }

    private static void findCodeExecutor(AnActionEvent e, Consumer<PyCodeExecutor> consumer, Editor editor, Project project, Module module) {
        if (project != null && editor != null) {
            if (canFindConsole(e)) {
                selectConsole(e.getDataContext(), project, consumer);
            }
            else {
                startConsole(project, consumer, module);
            }
        }
    }

    private static boolean isAlive(RunContentDescriptor dom) {
        ProcessHandler processHandler = dom.getProcessHandler();
        return processHandler != null && !processHandler.isProcessTerminated();
    }

    private static void startConsole(final Project project,
                                     final Consumer<PyCodeExecutor> consumer,
                                     Module context) {
        final PythonConsoleToolWindow toolWindow = PythonConsoleToolWindow.getInstance(project);

        if (toolWindow != null) {
            toolWindow.activate(new Runnable() {
                @Override
                public void run() {
                    List<RunContentDescriptor> descs = toolWindow.getConsoleContentDescriptors();

                    RunContentDescriptor descriptor = descs.get(0);
                    if (descriptor != null && descriptor.getExecutionConsole() instanceof PyCodeExecutor) {
                        consumer.consume((PyCodeExecutor)descriptor.getExecutionConsole());
                    }
                }
            });
        }
        else {
            PythonConsoleRunnerFactory consoleRunnerFactory = PythonConsoleRunnerFactory.getInstance();
            PydevConsoleRunner runner = consoleRunnerFactory.createConsoleRunner(project, null);
            runner.addConsoleListener(new PydevConsoleRunner.ConsoleListener() {
                @Override
                public void handleConsoleInitialized(LanguageConsoleView consoleView) {
                    if (consoleView instanceof PyCodeExecutor) {
                        consumer.consume((PyCodeExecutor)consoleView);
                    }
                }
            });
            runner.run(true);
        }
    }

    private static boolean canFindConsole(AnActionEvent e) {
        Project project = CommonDataKeys.PROJECT.getData(e.getDataContext());
        if (project != null) {
            Collection<RunContentDescriptor> descriptors = getConsoles(project);
            return descriptors.size() > 0;
        }
        else {
            return false;
        }
    }

    private static void executeInConsole(@NotNull PyCodeExecutor codeExecutor, @NotNull String text, Editor editor) {
        codeExecutor.executeCode(text, editor);
    }

    public static List<PydevCompletionVariant> complete(final Project project, final String text, final String actTok) {
        if (project == null) {
            return null;
        }

        Collection<RunContentDescriptor> consoles = getConsoles(project);
        if (!consoles.isEmpty()) {
            RunContentDescriptor descriptor = consoles.iterator().next();
            if (descriptor != null && descriptor.getProcessHandler() instanceof PyConsoleProcessHandler) {
                PyConsoleProcessHandler processHandler = (PyConsoleProcessHandler) descriptor.getProcessHandler();
                if (processHandler != null) {
                    return getCompletionsInConsole(processHandler.getPydevConsoleCommunication(), text, actTok);
                }
            }
        }
        return null;

    }

    private static List<PydevCompletionVariant> getCompletionsInConsole(@NotNull PydevConsoleCommunication consoleCommunication, @NotNull String text, @NotNull String actTok) {
        try {
            List<PydevCompletionVariant> completionVariants = consoleCommunication.getCompletions(text, actTok);
            // StringBuilder stringBuilder = new StringBuilder();
            // for (PydevCompletionVariant completionVariant : completionVariants) {
            //     stringBuilder.append(completionVariant.getName());
            //     stringBuilder.append(", ");
            // }
            // System.out.println("completionVariants: " + completionVariants.size() + ", content: " + stringBuilder.toString());
            return completionVariants;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static PyDebugValue getVariable(final Project project, final String variable) {
        if (project == null) {
            return null;
        }

        Collection<RunContentDescriptor> consoles = getConsoles(project);
        if (!consoles.isEmpty()) {
            RunContentDescriptor descriptor = consoles.iterator().next();
            if (descriptor != null && descriptor.getProcessHandler() instanceof PyConsoleProcessHandler) {
                PyConsoleProcessHandler processHandler = (PyConsoleProcessHandler) descriptor.getProcessHandler();
                if (processHandler != null) {
                    return getVariableInConsole(processHandler.getPydevConsoleCommunication(), variable);
                }
            }
        }
        return null;

    }

    private static PyDebugValue getVariableInConsole(@NotNull PydevConsoleCommunication consoleCommunication, @NotNull String variable) {
        try {
            PyDebugValue var = consoleCommunication.evaluate(variable, true, true);
            // XValueChildrenList result = consoleCommunication.loadVariable(var);
            ArrayList<PyFrameAccessor.PyAsyncValue<String>> valuesForEvaluation = new ArrayList<>();
            valuesForEvaluation.add(new PyFrameAccessor.PyAsyncValue<>(var, new PyDebugCallback<String>() {

                @Override
                public void ok(String value) {
                    var.setValue(value);
                    System.out.println("var name: " + GetVariableCommand.composeName(var) + ", result: " + value);
                }

                @Override
                public void error(PyDebuggerException exception) {

                }
            }));
            consoleCommunication.loadAsyncVariablesValues(valuesForEvaluation);
            return var;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
