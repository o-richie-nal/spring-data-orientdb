/*******************************************************************************
 * Copyright 2013-2014 the original author or authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.develspot.data.orientdb.convert;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.develspot.data.orientdb.common.OrientDataWrapper;
import org.develspot.data.orientdb.mapping.Connected;
import org.develspot.data.orientdb.mapping.OrientPersistentEntity;
import org.develspot.data.orientdb.mapping.OrientPersistentProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MapAccessor;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.convert.EntityInstantiator;
import org.springframework.data.convert.EntityInstantiators;
import org.springframework.data.mapping.Association;
import org.springframework.data.mapping.AssociationHandler;
import org.springframework.data.mapping.PropertyHandler;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mapping.model.BeanWrapper;
import org.springframework.data.mapping.model.DefaultSpELExpressionEvaluator;
import org.springframework.data.mapping.model.ParameterValueProvider;
import org.springframework.data.mapping.model.PersistentEntityParameterValueProvider;
import org.springframework.data.mapping.model.SpELContext;
import org.springframework.data.mapping.model.SpELExpressionEvaluator;
import org.springframework.data.util.ClassTypeInformation;
import org.springframework.data.util.TypeInformation;
import org.springframework.util.Assert;

import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.orient.OrientElement;
import com.tinkerpop.blueprints.impls.orient.OrientVertex;

public class MappingOrientConverter extends AbstractOrientConverter {

	public MappingOrientConverter(MappingContext<? extends OrientPersistentEntity<?>, OrientPersistentProperty> mappingContext,
						IConnectionResolver connectionResolver) {
		super(new DefaultConversionService());
		Assert.notNull(mappingContext, "MappingContext should not be null!");
		this.mappingContext = mappingContext;
		this.spELContext = new SpELContext(new MapAccessor());
		this.fieldAccessOnly = true;
		this.connectionResolver = connectionResolver;
	}
	
	public MappingContext<? extends OrientPersistentEntity<?>, OrientPersistentProperty> getMappingContext() {
		return mappingContext;
	}

	
	public <R> R read(Class<R> type, OrientElement source) {
		if(log.isTraceEnabled()) {
			log.trace("reading type: " + type + " from orientElement: " + source.getId());
		}
		return read(ClassTypeInformation.from(type), source);
		
	}
	
	@SuppressWarnings("unchecked")
	protected <S extends Object> S read(TypeInformation<S> typeInformation, OrientElement source) {
		OrientPersistentEntity<S> persistentEntity = (OrientPersistentEntity<S>)mappingContext.getPersistentEntity(typeInformation);
		return read(persistentEntity, source);
	}
	
	

	public void write(Object source, OrientElement sink) {
		// TODO Auto-generated method stub

	}

	private ParameterValueProvider<OrientPersistentProperty> getParameterProvider(OrientPersistentEntity<?> entity,
			OrientVertex source, DefaultSpELExpressionEvaluator evaluator) {

		OrientDBPropertyValueProvider provider = new OrientDBPropertyValueProvider(new OrientDataWrapper<OrientVertex>(source), evaluator, this);
		PersistentEntityParameterValueProvider<OrientPersistentProperty> parameterProvider = new PersistentEntityParameterValueProvider<OrientPersistentProperty>(
				entity, provider, null);

		return parameterProvider;
	}

	
	private <S extends Object> S read(final OrientPersistentEntity<S> entity, final OrientElement dbObject) {
		
		EntityInstantiator instantiator = instantiators.getInstantiatorFor(entity);
		final DefaultSpELExpressionEvaluator evaluator = new DefaultSpELExpressionEvaluator(dbObject, spELContext);
		
		ParameterValueProvider<OrientPersistentProperty> parameterProvider = getParameterProvider(entity, (OrientVertex)dbObject, evaluator);
		S instance = instantiator.createInstance(entity, parameterProvider);
		
		final BeanWrapper<OrientPersistentEntity<S>, S> wrapper = BeanWrapper.create(instance, conversionService);
		final S result = wrapper.getBean();
		// Set properties not already set in the constructor
		entity.doWithProperties(new PropertyHandler<OrientPersistentProperty>() {
			public void doWithPersistentProperty(OrientPersistentProperty prop) {
				if(prop.isIdProperty()) {
					//TODO custom provider for id properties
					wrapper.setProperty(prop, readValue(dbObject.getId(), prop.getTypeInformation()));
					return;
				}
				
				
				if (dbObject.getProperty(prop.getField().getName()) == null || entity.isConstructorArgument(prop)) {
					return;
				}

				OrientDataWrapper<OrientVertex> dbo = new OrientDataWrapper<OrientVertex>((OrientVertex) dbObject);
				Object obj = getValueInternal(prop, dbo, evaluator, null);
				wrapper.setProperty(prop, obj, fieldAccessOnly);
			}
		});
		
		entity.doWithAssociations(new AssociationHandler<OrientPersistentProperty>() {
			
			public void doWithAssociation(
					Association<OrientPersistentProperty> association) {
				OrientPersistentProperty inverseProp = association.getInverse();
				
				Object resolved = connectionResolver.resolveConnected(inverseProp, new IConnectionResolverCallback() {
					
					public Object resolve(OrientPersistentProperty property) {
						return fetchConnected((OrientVertex) dbObject,property);
						
					}
				});
				
				OrientDataWrapper<OrientVertex> entityList = new OrientDataWrapper<OrientVertex>((Set<OrientVertex>)resolved);
				
				Object value = getValueInternal(inverseProp, entityList, evaluator, null);
				wrapper.setProperty(inverseProp, value);
			}
		});
		
		
		return result;
	}
	
	protected Object getValueInternal(OrientPersistentProperty prop, OrientDataWrapper<OrientVertex> dbo, SpELExpressionEvaluator eval,
			Object parent) {

		OrientDBPropertyValueProvider provider = new OrientDBPropertyValueProvider( dbo, spELContext, this);
		return provider.getPropertyValue(prop);
	}
	

	
	Set<OrientVertex> fetchConnected(OrientVertex source, OrientPersistentProperty property) {
		Connected connected = property.getConnected();
		
		HashSet<OrientVertex> result = new HashSet<OrientVertex>();
		Iterable<Vertex> vertices = source.getVertices(connected.direction(), connected.edgeType());
		
		Iterator<Vertex> iterator = vertices.iterator();
		while(iterator.hasNext()) {
			result.add((OrientVertex) iterator.next());
		}
		return result;
	}
	
	@SuppressWarnings("unchecked")
	private <T> T readValue(Object value, TypeInformation<?> type) {
		Class<?> target = type.getType();
		return target.isAssignableFrom(value.getClass()) ? (T)value : (T)conversionService.convert(value, target);	
	}

	
	
	
	protected EntityInstantiators instantiators = new EntityInstantiators();
	
	private final MappingContext<? extends OrientPersistentEntity<?>, OrientPersistentProperty> mappingContext;
	private SpELContext spELContext;
	private boolean fieldAccessOnly;
	
	private IConnectionResolver connectionResolver;
	
	private static final Logger log = LoggerFactory.getLogger(MappingOrientConverter.class);
}
