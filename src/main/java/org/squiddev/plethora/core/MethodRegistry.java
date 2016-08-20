package org.squiddev.plethora.core;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.*;
import net.minecraft.util.Tuple;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.common.discovery.ASMDataTable;
import org.objectweb.asm.Type;
import org.squiddev.plethora.api.Constants;
import org.squiddev.plethora.api.PlethoraAPI;
import org.squiddev.plethora.api.method.*;
import org.squiddev.plethora.api.reference.IReference;
import org.squiddev.plethora.api.reference.Reference;
import org.squiddev.plethora.core.capabilities.DefaultCostHandler;
import org.squiddev.plethora.utils.DebugLogger;
import org.squiddev.plethora.utils.Helpers;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.util.*;

public final class MethodRegistry implements IMethodRegistry {
	public static final MethodRegistry instance = new MethodRegistry();
	private final IReference<?>[] emptyReference = new IReference[0];

	private final Multimap<Class<?>, IMethod<?>> providers = MultimapBuilder.hashKeys().arrayListValues().build();

	@Override
	public <T> void registerMethod(@Nonnull Class<T> target, @Nonnull IMethod<T> method) {
		Preconditions.checkNotNull(target, "target cannot be null");
		Preconditions.checkNotNull(method, "method cannot be null");

		providers.put(target, method);

		if (target == Object.class && !(method instanceof IConverterExcludeMethod)) {
			DebugLogger.warn(
				"You're registering a method (" + method + ") targeting the base class (Object). Converters will " +
					"probably mask the original object: it is recommended that you implement IConverterExcludeMethod to avoid this."
			);
		}

		if (ConfigCore.Testing.likeDocs && method.getDocString() == null) {
			String message = "Method " + method + " (" + method.getName() + ") has no documentation. This isn't a bug but you really should add one.";
			if (ConfigCore.Testing.strict) {
				throw new IllegalArgumentException(message);
			} else {
				DebugLogger.error(message);
			}
		}
	}

	@Nonnull
	@Override
	@SuppressWarnings("unchecked")
	public <T> List<IMethod<T>> getMethods(@Nonnull IContext<T> context) {
		Preconditions.checkNotNull(context, "context cannot be null");

		List<IMethod<T>> methods = Lists.newArrayList();

		for (IMethod<?> genMethod : getMethods(context.getTarget().getClass())) {
			final IMethod<T> method = (IMethod<T>) genMethod;
			if (method.canApply(context)) methods.add(method);
		}

		return Collections.unmodifiableList(methods);
	}

	@Nonnull
	@Override
	public List<IMethod<?>> getMethods(@Nonnull Class<?> target) {
		Preconditions.checkNotNull(target, "target cannot be null");

		List<IMethod<?>> result = Lists.newArrayList();

		HashSet<Class<?>> visited = Sets.newHashSet();
		Queue<Class<?>> toVisit = Queues.newArrayDeque();

		visited.add(target);
		toVisit.add(target);

		while (toVisit.size() > 0) {
			Class<?> klass = toVisit.poll();
			result.addAll(providers.get(klass));

			Class<?> parent = klass.getSuperclass();
			if (parent != null && visited.add(parent)) {
				toVisit.add(parent);
			}

			for (Class<?> iface : klass.getInterfaces()) {
				if (iface != null && visited.add(iface)) {
					toVisit.add(iface);
				}
			}
		}

		return Collections.unmodifiableList(result);
	}

	@Nonnull
	@Override
	public Multimap<Class<?>, IMethod<?>> getMethods() {
		return MultimapBuilder.hashKeys().arrayListValues().build(providers);
	}

	@Nonnull
	@Override
	public <T> IUnbakedContext<T> makeContext(@Nonnull IReference<T> target, @Nonnull ICostHandler handler, @Nonnull IReference<?>... context) {
		Preconditions.checkNotNull(target, "target cannot be null");
		Preconditions.checkNotNull(handler, "handler cannot be null");
		Preconditions.checkNotNull(context, "context cannot be null");
		return new UnbakedContext<T>(target, handler, context);
	}

	@Nonnull
	@Override
	public ICostHandler getCostHandler(@Nonnull ICapabilityProvider object) {
		Preconditions.checkNotNull(object, "object cannot be null");
		ICostHandler handler = object.getCapability(Constants.COST_HANDLER_CAPABILITY, null);
		return handler != null ? handler : DefaultCostHandler.get(object);
	}

	@Override
	public <T extends Annotation> void registerMethodBuilder(@Nonnull Class<T> klass, @Nonnull IMethodBuilder<T> builder) {
		Preconditions.checkNotNull(klass, "klass cannot be null");
		Preconditions.checkNotNull(builder, "builder cannot be null");

		MethodTypeBuilder.instance.addBuilder(klass, builder);
	}

	public Tuple<List<IMethod<?>>, List<IUnbakedContext<?>>> getMethodsPaired(IUnbakedContext<?> initialContext, IContext<?> initialBaked) {
		ArrayList<IMethod<?>> methods = Lists.newArrayList();
		ArrayList<IUnbakedContext<?>> contexts = Lists.newArrayList();
		HashMap<String, Integer> methodLookup = new HashMap<String, Integer>();

		Object initialTarget = initialBaked.getTarget();
		for (Object obj : PlethoraAPI.instance().converterRegistry().convertAll(initialTarget)) {
			IUnbakedContext<?> ctx = null;
			IContext<?> ctxBaked;

			boolean isInitial = obj == initialTarget;
			if (isInitial) {
				ctxBaked = initialBaked;
			} else {
				ctxBaked = initialBaked.makeBakedChild(obj);
			}

			for (IMethod method : getMethods(ctxBaked)) {
				// Skip IConverterExclude methods
				if (!isInitial && method instanceof IConverterExcludeMethod) {
					continue;
				}

				// Lazy load context
				if (ctx == null) {
					ctx = isInitial ? initialContext : initialContext.makeChild(Reference.id(obj));
				}

				Integer existing = methodLookup.get(method.getName());
				if (existing != null) {
					int index = existing;
					if (method.getPriority() > methods.get(index).getPriority()) {
						methods.set(index, method);
						contexts.set(index, ctx);
					}
				} else {
					methods.add(method);
					contexts.add(ctx);
					methodLookup.put(method.getName(), methods.size() - 1);
				}
			}
		}

		if (methods.size() > 0) {
			IMethodCollection collection = new MethodCollection(methods);
			IUnbakedContext<IMethodCollection> ctx = null;
			IContext<IMethodCollection> baked = new Context<IMethodCollection>(null, collection, initialBaked.getCostHandler(), emptyReference);
			for (IMethod method : getMethods(baked)) {
				if (ctx == null) {
					ctx = new UnbakedContext<IMethodCollection>(Reference.id(collection), initialBaked.getCostHandler(), emptyReference);
				}

				Integer existing = methodLookup.get(method.getName());
				if (existing != null) {
					int index = existing;
					if (method.getPriority() > methods.get(index).getPriority()) {
						methods.set(index, method);
						contexts.set(index, ctx);
					}
				} else {
					methods.add(method);
					contexts.add(ctx);
					methodLookup.put(method.getName(), methods.size() - 1);
				}
			}
		}

		return new Tuple<List<IMethod<?>>, List<IUnbakedContext<?>>>(methods, contexts);
	}

	@SuppressWarnings("unchecked")
	public void loadAsm(ASMDataTable asmDataTable) {
		for (ASMDataTable.ASMData asmData : asmDataTable.getAll(IMethod.Inject.class.getName())) {
			String name = asmData.getClassName();
			try {
				if (Helpers.classBlacklisted(ConfigCore.Blacklist.blacklistProviders, name)) {
					DebugLogger.debug("Ignoring " + name + " as it has been blacklisted");
					continue;
				}

				Map<String, Object> info = asmData.getAnnotationInfo();
				String modId = (String) info.get("modId");
				if (!Strings.isNullOrEmpty(modId) && !Helpers.modLoaded(modId)) {
					DebugLogger.debug("Skipping " + name + " as " + modId + " is not loaded or is blacklisted");
					continue;
				}

				DebugLogger.debug("Registering " + name);

				Class<?> asmClass = Class.forName(name);
				IMethod instance = asmClass.asSubclass(IMethod.class).newInstance();

				Class<?> target = Class.forName(((Type) info.get("value")).getClassName());
				registerMethod(target, instance);
			} catch (Throwable e) {
				if (ConfigCore.Testing.strict) {
					throw new IllegalStateException("Failed to load: " + name, e);
				} else {
					DebugLogger.error("Failed to load: " + name, e);
				}
			}
		}
	}
}
