package com.axelor.db;

import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.OneToMany;
import javax.persistence.OptimisticLockException;
import javax.persistence.PersistenceException;

import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.StaleObjectStateException;
import org.hibernate.collection.PersistentCollection;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;

import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.inject.Provider;

/**
 * This class provides easy access to {@link EntityManager} and related API. It
 * also provides some convenient methods create {@link Model} instances or
 * creating {@link Query}.
 * 
 * This class should be initialized using Guice container as eager singleton
 * during application startup.
 * 
 */
@Singleton
public final class JPA {

	private Provider<EntityManager> emp;
	private static JPA INSTANCE = null;

	@Inject
	private JPA(Provider<EntityManager> emp) {
		this.emp = emp;
		INSTANCE = this;
	}

	private static JPA get() {
		if (INSTANCE == null) {
			throw new RuntimeException("JPA context not initialized.");
		}
		return INSTANCE;
	}

	/**
	 * Get an instance of {@link EntityManager}.
	 * 
	 */
	public static EntityManager em() {
		return get().emp.get();
	}

	/**
	 * Execute a JPQL update query.
	 * 
	 * @param query
	 *            JPQL query
	 */
	public static int execute(String query) {
		return em().createQuery(query).executeUpdate();
	}

	/**
	 * Prepare a {@link Query} for the given model class.
	 * 
	 * @param klass
	 *            the model class
	 */
	public static <T extends Model> Query<T> all(Class<T> klass) {
		return Query.of(klass);
	}

	/**
	 * Find by primary key.
	 * 
	 * @see EntityManager#find(Class, Object)
	 */
	public static <T extends Model> T find(Class<T> klass, Long id) {
		return em().find(klass, id);
	}

	/**
	 * Make an entity managed and persistent.
	 * 
	 * @see EntityManager#persist(Object)
	 */
	public static <T extends Model> T persist(T entity) {
		// optimistic concurrency check
		if (entity != null)
			checkVersion(entity.getClass(), entity.getId(), entity.getVersion());
		em().persist(entity);
		em().flush();
		return entity;
	}

	/**
	 * Merge the state of the given entity into the current persistence context.
	 * 
	 * @see EntityManager#merge(Object)
	 */
	public static <T extends Model> T merge(T entity) {
		// optimistic concurrency check
		if (entity != null)
			checkVersion(entity.getClass(), entity.getId(), entity.getVersion());
		T result = em().merge(entity);
		em().flush();
		return result;
	}

	/**
	 * Merge or persist the given entity.
	 * 
	 * @see #persist(Model)
	 * @see #merge(Model)
	 */
	public static <T extends Model> T save(T entity) {
		if (entity.getId() != null) {
			return merge(entity);
		}
		return persist(entity);
	}

	/**
	 * Remove the entity instance.
	 * 
	 * @see EntityManager#remove(Object)
	 */
	public static <T extends Model> void remove(T entity) {
		EntityManager manager = em();
		if (manager.contains(entity)) {
			manager.remove(entity);
		} else {
			// optimistic concurrency check
			checkVersion(entity.getClass(), entity.getId(), entity.getVersion());
			Model attached = manager.find(entity.getClass(), entity.getId());
			manager.remove(attached);
		}
	}

	/**
	 * Refresh the state of the instance from the database, overwriting changes
	 * made to the entity, if any.
	 * 
	 * @see EntityManager#refresh(Object)
	 */
	public static <T extends Model> void refresh(T entity) {
		em().refresh(entity);
	}
	
	/**
	 * Synchronize the persistence context to the underlying database.
	 * 
	 * @see EntityManager#flush()
	 */
	public static void flush() {
		em().flush();
	}
	
	/**
	 * Clear the persistence context, causing all managed
     * entities to become detached.
     * 
     * @see EntityManager#clear()
	 */
	public static void clear() {
		em().clear();
	}
	
	private static <T extends Model> void checkVersion(Class<T> klass, Long id, Object version) {
		if (id == null || version == null) {
			return;
		}
		T entity = JPA.find(klass, id);
		if (entity == null || !Objects.equal(version, entity.getVersion())) {
			Exception cause = new StaleObjectStateException(klass.getName(), id);
			throw new OptimisticLockException(cause);
		}
	}
	
	/**
	 * Edit an instance of the given model class using the given values.
	 * 
	 * This is a convenient method to reconstruct model object from a key value
	 * map, for example HTTP params.
	 * 
	 * @param klass
	 *            a model class
	 * @param values
	 *            key value map where key represents a field name
	 * @return a JPA managed object of the given model class
	 */
	public static <T extends Model> T edit(Class<T> klass, Map<String, Object> values) {
		Set<Model> visited = Sets.newHashSet();
		try {
			return _edit(klass, values, visited);
		} finally {
			visited.clear();
		}
	}

	@SuppressWarnings("all")
	private static <T extends Model> T _edit(Class<T> klass, Map<String, Object> values, Set<Model> visited) {

		if (values == null)
			return null;

		Mapper mapper = Mapper.of(klass);
		Long id = null;
		T bean = null;

		try {
			id = Long.valueOf(values.get("id").toString());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(e);
		} catch (NullPointerException e) {
		}
		
		if (id == null) {
			try {
				bean = klass.newInstance();
			} catch (Exception ex) {
				throw new IllegalArgumentException(ex);
			}
		} else
			bean = JPA.em().find(klass, id);
		
		// optimistic concurrency check
		Integer beanVersion = (Integer) values.get("version");
		boolean beanChanged = false;
				
		if (visited.contains(bean) && beanVersion == null) {
			return bean;
		}
		visited.add(bean);
		
		for (String name : values.keySet()) {

			Property p = mapper.getProperty(name);
			if (p == null || p.isVersion() || mapper.getSetter(name) == null)
				continue;

			Object value = values.get(name);
			Class<Model> target = (Class<Model>) p.getTarget();

			if (p.isCollection()) {

				Collection items = new ArrayList();
				if (Set.class.isAssignableFrom(p.getJavaType()))
					items = new HashSet();

				if (value instanceof Collection) {
					for (Object val : (Collection) value) {
						if (val instanceof Map) {
							if (p.getMappedBy() != null) {
								if (val instanceof ImmutableMap)
									val = Maps.newHashMap((Map) val);
								((Map) val).remove(p.getMappedBy());
							}
							Model item = _edit(target, (Map) val, visited);
							items.add(p.setAssociation(item, bean));
						} else if (val instanceof Number) {
							items.add(JPA.find(target,
									Long.parseLong(val.toString())));
						}
					}
				}

				Object old = mapper.get(bean, name);
				if (old instanceof Collection) {
					boolean changed = ((Collection) old).size() != items.size();
					if (!changed) {
						for(Object item : items) {
							if (!((Collection) old).contains(item)) {
								changed = true;
								break;
							}
						}
					}
					if (changed) {
						((Collection) old).clear();
						((Collection) old).addAll(items);
						beanChanged = true;
					}
					continue;
				}
				value = items;
			} else if (value instanceof Map) {
				value = _edit(target, (Map) value, visited);
			}
			Object oldValue = mapper.set(bean, name, value);
			if (p.valueChanged(bean, oldValue)) {
				beanChanged = true;
			}
		}
		
		if (beanChanged) {
			checkVersion(klass, id, beanVersion);
		}
		
		return bean;
	}

	/**
	 * A convenient method to persist reconstructed unmanaged objects.
	 * 
	 * This method takes care of relational fields by inspecting the managed
	 * state of the referenced objects and also sets reverse lookup fields
	 * annotated with {@link OneToMany#mappedBy()} annotation.
	 * 
	 * @see JPA#edit(Class, Map)
	 * 
	 * @param bean
	 *            model instance
	 * @return JPA managed model instance
	 */
	public static <T extends Model> T manage(T bean) {
		Set<Model> visited = Sets.newHashSet();
		try {
			return persist(_manage(bean, visited));
		} finally {
			visited.clear();
		}
	}

	private static <T extends Model> T _manage(T bean, Set<Model> visited) {

		if (visited.contains(bean))
			return bean;
		visited.add(bean);
		
		if (bean instanceof HibernateProxy
				&& ((HibernateProxy) bean).getHibernateLazyInitializer().isUninitialized())
			return bean;

		Mapper mapper = Mapper.of(bean.getClass());
		for (Property property : mapper.getProperties()) {
			
			if (property.getTarget() == null || property.isReadonly())
				continue;

			Object value = property.get(bean);
			if (value == null)
				continue;

			if (value instanceof PersistentCollection
					&& !((PersistentCollection) value).wasInitialized())
				continue;
			
			// bind M2O
			if (property.isReference()) {
				_manage((Model) value, visited);
			}
			
			// bind O2M & M2M
			else if (property.isCollection()) {
				for (Object val : (Collection<?>) value) {
					_manage(property.setAssociation((Model) val, bean), visited);
				}
			}
		}
		return bean;
	}

	/**
	 * Return all the non-abstract models found in all the activated modules.
	 * 
	 * @return Set of model classes
	 */
	public static Set<Class<?>> models() {

		return Sets.filter(JpaScanner.findModels(), new Predicate<Class<?>>() {

			@Override
			public boolean apply(Class<?> input) {
				return !Modifier.isAbstract(input.getModifiers());
			}
		});
	}

	/**
	 * Return all the properties of the given model class.
	 * 
	 */
	public static <T extends Model> Property[] fields(Class<T> klass) {
		return Mapper.of(klass).getProperties();
	}

	/**
	 * Return a {@link Property} of the given model class.
	 * 
	 * @param klass
	 *            a model class
	 * @param name
	 *            name of the property
	 * @return property or null if property doesn't exist
	 */
	public static <T extends Model> Property field(Class<T> klass, String name) {
		return Mapper.of(klass).getProperty(name);
	}

	/**
	 * Create a duplicate copy of the given bean instance.
	 * 
	 * In case of deep copy, one-to-many records are duplicated. Otherwise,
	 * one-to-many records will be skipped.
	 * 
	 * @param bean the bean to copy
	 * @param deep whether to create a deep copy
	 * @return a copy of the given bean
	 */
	public static <T extends Model> T copy(T bean, boolean deep) {
		Set<String> visited = Sets.newHashSet();
		try {
			return _copy(bean, deep, visited);
		} finally {
			visited.clear();
		}
	}

	@SuppressWarnings("all")
	private static <T extends Model> T _copy(T bean, boolean deep, Set<String> visited) {

		if (bean == null) {
			return bean;
		}
		
		Class<?> beanClass = bean.getClass();
		
		if (bean instanceof HibernateProxy) {
			LazyInitializer proxy = ((HibernateProxy) bean).getHibernateLazyInitializer();
			if (proxy.isUninitialized())
				bean = (T) proxy.getImplementation();
			beanClass = proxy.getPersistentClass();
		}
		
		String key = beanClass.getName() + "#" + bean.getId();
		if (visited.contains(key))
			return null;
		visited.add(key);
		
		Mapper mapper = Mapper.of(beanClass);
		final T obj = Mapper.toBean((Class<T>) beanClass, null);
		
		for(Property p : mapper.getProperties()) {
			
			if (p.isVirtual() || p.isPrimary() || p.isVersion())
				continue;

			Object value = p.get(bean);
			
			if (value instanceof Set) {
				value = new HashSet((Set) value);
			}
			
			else if (value instanceof List) {
				if (deep) {
					value = Lists.transform((List<?>) value,
							new Function<Object, Object>() {
								@Override
								public Object apply(Object input) {
									return copy((Model) input, true);
								}
							});
				} else
					value = null;
			}

			if (value instanceof String && p.isUnique()) {
				value = ((String) value) + " Copy (" + bean.getId() + ")";
			}

			p.set(obj, value);
		}

		return obj;
	}

	/**
	 * Run the given <code>task</code> inside a transaction that is committed
	 * after the task is completed.
	 * 
	 * @param task
	 *            the task to run.
	 */
	public static void runInTransaction(Runnable task) {
		Preconditions.checkNotNull(task);
		EntityTransaction txn = em().getTransaction();
		boolean txnStarted = false;
		try {
			if (!txn.isActive()) {
				txn.begin();
				txnStarted = true;
			}
			task.run();
			if (txnStarted && txn.isActive() && !txn.getRollbackOnly()) {
				txn.commit();
			}
		} finally {
			if (txnStarted && txn.isActive()) {
				txn.rollback();
			}
		}
	}

	/**
	 * Perform JDBC related work using the {@link Connection} managed by the current
	 * {@link EntityManager}.
	 * 
	 * @param work
	 *            The work to be performed
	 * @throws PersistenceException
	 *             Generally indicates wrapped {@link SQLException}
	 */
	public static void jdbcWork(final JDBCWork work) {
		Session session = (Session) em().getDelegate();
		try {
			session.doWork(new org.hibernate.jdbc.Work() {
				@Override
				public void execute(Connection connection) throws SQLException {
					work.execute(connection);
				}
			});
		} catch (HibernateException e) {
			throw new PersistenceException(e);
		}
	}

	public static interface JDBCWork {

		/**
		 * Execute the discrete work encapsulated by this work instance using
		 * the supplied connection.
		 * <p>
		 * Generally, you should not close the connection as it's being used by
		 * the current {@link EntityManager}.
		 * 
		 * @param connection
		 *            The connection on which to perform the work.
		 * @throws SQLException
		 *             Thrown during execution of the underlying JDBC
		 *             interaction.
		 */
		void execute(Connection connection) throws SQLException;
	}
}
