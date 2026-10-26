package sqlens.eclipse.ui;

import java.lang.reflect.Method;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import sqlens.eclipse.Activator;

public class RunQueryHandler extends AbstractHandler {

    private static final String PROBLEMS_VIEW = "org.eclipse.ui.views.ProblemView";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        String sql = EditorStatement.of(event);
        if (sql != null) {
            resultsView(event).runQuery(sql);
        }
        return null;
    }

    static ResultsView resultsView(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
        try {
            boolean opening = page.findView(ResultsView.ID) == null;
            // Visible but not active, so the cursor stays in the editor.
            ResultsView view = (ResultsView) page.showView(ResultsView.ID, null, IWorkbenchPage.VIEW_VISIBLE);
            if (opening) {
                stackWithProblems(view);
            }
            return view;
        } catch (PartInitException e) {
            throw new ExecutionException("Cannot open the SQLens Results view", e);
        }
    }

    /**
     * A view opens where the perspective puts new views; move it to the bottom, next to Problems.
     * The Eclipse 4 model is reached by reflection through the workbench's class loader: the
     * bundle's own wiring is fixed when it is first installed, and this needs no new dependencies.
     */
    private static void stackWithProblems(ResultsView view) {
        try {
            ClassLoader loader = PlatformUI.class.getClassLoader();
            Class<?> uiElement = loader.loadClass("org.eclipse.e4.ui.model.application.ui.MUIElement");
            Class<?> container = loader.loadClass("org.eclipse.e4.ui.model.application.ui.MElementContainer");
            Class<?> part = loader.loadClass("org.eclipse.e4.ui.model.application.ui.basic.MPart");
            Class<?> placeholder = loader.loadClass("org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder");
            Class<?> window = loader.loadClass("org.eclipse.e4.ui.model.application.ui.basic.MWindow");
            Class<?> partStack = loader.loadClass("org.eclipse.e4.ui.model.application.ui.basic.MPartStack");
            Class<?> modelService = loader.loadClass("org.eclipse.e4.ui.workbench.modeling.EModelService");

            Object model = view.getSite().getService(modelService);
            Object myPart = view.getSite().getService(part);
            Object mainWindow = view.getSite().getWorkbenchWindow().getService(window);
            if (model == null || myPart == null || mainWindow == null) {
                return;
            }
            // A part lives in the window's shared elements; each perspective has a placeholder for it in a stack.
            Object problems = call(modelService, "find", model, PROBLEMS_VIEW, mainWindow);
            if (problems != null && !placeholder.isInstance(problems)) {
                problems = call(modelService, "findPlaceholderFor", model, mainWindow, problems);
            }
            Object mine = call(modelService, "findPlaceholderFor", model, mainWindow, myPart);
            Object stack = problems == null ? null : call(uiElement, "getParent", problems);
            if (mine != null && partStack.isInstance(stack) && stack != call(uiElement, "getParent", mine)) {
                call(modelService, "move", model, mine, stack);
                call(container, "setSelectedElement", stack, mine);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Staying where the perspective put it is fine.
            Activator.getDefault().getLog().warn("Cannot move the SQLens Results view next to Problems", e);
        }
    }

    private static Object call(Class<?> type, String name, Object target, Object... args) throws ReflectiveOperationException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
