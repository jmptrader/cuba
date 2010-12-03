/*
 * Copyright (c) 2008 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.

 * Author: Dmitry Abramov
 * Created: 30.03.2009 11:50:28
 * $Id$
 */

package com.haulmont.cuba.gui.data.impl;

import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.chile.core.model.MetaPropertyPath;
import com.haulmont.chile.core.model.Instance;
import com.haulmont.chile.core.model.utils.InstanceUtils;
import com.haulmont.cuba.core.entity.Entity;
import com.haulmont.cuba.core.global.*;
import com.haulmont.cuba.gui.data.*;
import org.apache.commons.collections.map.LinkedMap;
import org.perf4j.StopWatch;
import org.perf4j.log4j.Log4JStopWatch;

import java.util.*;

public class LazyCollectionDatasource<T extends Entity<K>, K>
    extends
        AbstractCollectionDatasource<T, K>
    implements
        CollectionDatasource.Sortable<T, K>,
        CollectionDatasource.Lazy<T, K>,
        CollectionDatasource.Suspendable<T, K>
{
    protected LinkedMap data = new LinkedMap();
    protected Integer size;

    protected Map<String, Object> params = Collections.emptyMap();

    protected int chunk = 50;

    private Map<String, Object> savedParameters;

    private boolean inRefresh;

    protected boolean suspended;

    protected boolean refreshOnResumeRequired;

    public LazyCollectionDatasource(
            DsContext dsContext, com.haulmont.cuba.gui.data.DataService dataservice,
                String id, MetaClass metaClass, String viewName)
    {
        super(dsContext, dataservice, id, metaClass, viewName);
    }

    public LazyCollectionDatasource(
            DsContext dsContext, com.haulmont.cuba.gui.data.DataService dataservice,
                String id, MetaClass metaClass, View view)
    {
        super(dsContext, dataservice, id, metaClass, view);
    }

    public LazyCollectionDatasource(
            DsContext dsContext, com.haulmont.cuba.gui.data.DataService dataservice,
                String id, MetaClass metaClass, String viewName, boolean softDeletion)
    {
        super(dsContext, dataservice, id, metaClass, viewName);
        setSoftDeletion(softDeletion);
    }

    public LazyCollectionDatasource(
            DsContext dsContext, com.haulmont.cuba.gui.data.DataService dataservice,
                String id, MetaClass metaClass, View view, boolean softDeletion)
    {
        super(dsContext, dataservice, id, metaClass, view);
        setSoftDeletion(softDeletion);
    }

    public void addItem(T item) throws UnsupportedOperationException {
        checkState();

        attachListener((Instance) item);

        data.put(item.getId(), item);
        
        if (size != null)
            size++;

        if (sortInfos != null)
            sortInMemory();

        if (PersistenceHelper.isNew(item)) {
            itemToCreate.add(item);
        }

        modified = true;
        forceCollectionChanged(CollectionDatasourceListener.Operation.ADD);
    }

    public void removeItem(T item) throws UnsupportedOperationException {
        checkState();

        data.remove(item.getId());
        detachListener((Instance) item);

        if (size != null && size > 0)
            size--;

        deleted(item);

        forceCollectionChanged(CollectionDatasourceListener.Operation.REMOVE);
    }

    public void modifyItem(T item) {
        if (data.containsKey(item.getId())) {
            if (PersistenceHelper.isNew(item)) {
                Object existingItem = data.get(item.getId());
                InstanceUtils.copy((Instance) item, (Instance) existingItem);
                modified((T) existingItem);
            } else {
                updateItem(item);
                modified(item);
            }
        }
    }

    public void updateItem(T item) {
        checkState();

        if (data.containsKey(item.getId())) {
            data.put(item.getId(), item);
            attachListener((Instance) item);
            forceCollectionChanged(CollectionDatasourceListener.Operation.REFRESH);
        }
    }

    public boolean containsItem(K itemId) {
        return data.containsKey(itemId);
    }

    public void refreshIfNotSuspended() {
        if (suspended) {
            if (!state.equals(State.VALID)) {
                state = State.VALID;
            }
            refreshOnResumeRequired = true;
        } else {
            refresh();
        }
    }

    @Override
    public synchronized void refresh() {
        if (savedParameters == null)
            refresh(Collections.<String, Object>emptyMap());
        else
            refresh(savedParameters);
    }

    public synchronized void refresh(Map<String, Object> parameters) {
        this.params = parameters;
        if (inRefresh)
            return;

        inRefresh = true;
        try {
            savedParameters = parameters;

            size = null;
            for (Object entity : data.values()) {
                detachListener((Instance) entity);
            }
            data.clear();

            invalidate();

            suspended = false;
            refreshOnResumeRequired = false;

            getSize();
            if (!State.VALID.equals(state))
                loadNextChunk(false);

            if (sortInfos != null && sortInfos.length > 0 && isCompletelyLoaded())
                sortInMemory();

            forceCollectionChanged(CollectionDatasourceListener.Operation.REFRESH);
        } finally {
            inRefresh = false;
        }
    }

    @Override
    public synchronized void invalidate() {
        super.invalidate();
    }

    private int getSize() {
        if (suspended)
            return 0;

        if (size == null) {
            LoadContext context = new LoadContext(metaClass);
            LoadContext.Query q = createLoadContextQuery(context, savedParameters == null ? Collections.<String, Object>emptyMap() : savedParameters);
            if (q == null)
                return 0;

            QueryTransformer transformer = QueryTransformerFactory.createTransformer(q.getQueryString(), metaClass.getName());
            transformer.replaceWithCount();
            String jpqlQuery = transformer.getResult();
            q.setQueryString(jpqlQuery);

            List res = dataservice.loadList(context);
            int realSize = res.isEmpty() ? 0 : ((Long) res.get(0)).intValue();
            if (maxResults == 0)
                size = realSize;
            else
                size = Math.min(realSize, maxResults);
        }

        return size;
    }

    public synchronized T getItem(K key) {
        if (State.NOT_INITIALIZED.equals(state)) {
            throw new IllegalStateException("Invalid datasource state " + state);
        } else {
            T item = (T) data.get(key);
            return item;
        }
    }

    public K getItemId(T item) {
        return item == null ? null : item.getId();
    }

    public synchronized Collection<K> getItemIds() {
        if (State.NOT_INITIALIZED.equals(state)) {
            return Collections.emptyList();
        } else {
            if (!isCompletelyLoaded()) loadNextChunk(true);
            //noinspection unchecked
            return data.keySet();
        }
    }

    public synchronized int size() {
        if (State.NOT_INITIALIZED.equals(state)) {
            return 0;
        } else {
            return size == null ? 0 : size;
        }
    }

    public synchronized K nextItemId(K itemId) {
        @SuppressWarnings({"unchecked"})
        K nextId = (K) data.nextKey(itemId);
        if (nextId == null && !isCompletelyLoaded()) {
            loadNextChunk(false);
            //noinspection unchecked
            nextId = (K) data.nextKey(itemId);
        }
        return nextId;
    }

    public synchronized K prevItemId(K itemId) {
        //noinspection unchecked
        return (K) data.previousKey(itemId);
    }

    public synchronized K firstItemId() {
        if (suspended)
            return null;

        if (data.isEmpty())
            loadNextChunk(false);

        if (!data.isEmpty()) {
            //noinspection unchecked
            return (K) data.firstKey();
        } else {
            return null;
        }
    }

    public synchronized K lastItemId() {
        if (!isCompletelyLoaded())
            loadNextChunk(true);

        if (!data.isEmpty()) {
            //noinspection unchecked
            return (K) data.lastKey();
        } else {
            return null;
        }
    }

    public boolean isFirstId(K itemId) {
        return itemId != null && itemId.equals(firstItemId());
    }

    public boolean isLastId(K itemId) {
        //noinspection SimplifiableConditionalExpression
        return itemId != null && (isCompletelyLoaded() ? itemId.equals(lastItemId()) : false);
    }

    public boolean isCompletelyLoaded() {
        return data.size() == getSize();
    }

    protected void loadNextChunk(boolean all) {
        StopWatch sw = new Log4JStopWatch("LCDS " + id);

        getSize(); // ensure size is loaded

        LoadContext ctx = new LoadContext(metaClass);
        LoadContext.Query q = createLoadContextQuery(ctx, params);
        if (q != null) {
            if (sortInfos != null) {
                QueryTransformer transformer = QueryTransformerFactory.createTransformer(q.getQueryString(), metaClass.getName());

                boolean asc = Order.ASC.equals(sortInfos[0].getOrder());
                MetaPropertyPath propertyPath = sortInfos[0].getPropertyPath();

                transformer.replaceOrderBy(propertyPath.toString(), !asc);
                String jpqlQuery = transformer.getResult();

                q.setQueryString(jpqlQuery);
            }

            if (maxResults == 0 || data.size() < maxResults) {
                ctx.getQuery().setFirstResult(data.size());
                ctx.setView(view);

                if (all) {
                    if (maxResults > 0)
                        ctx.getQuery().setMaxResults(maxResults);
                } else
                    ctx.getQuery().setMaxResults(chunk);

                List<T> res = dataservice.loadList(ctx);
                for (T t : res) {
                    data.put(t.getId(), t);
                    attachListener((Instance) t);
                }

                if (res.size() < chunk || (maxResults > 0 && data.size() >= maxResults)) {
                    size = data.size(); // all is loaded
                    for (DatasourceListener listener : dsListeners) {
                        if (listener instanceof LazyCollectionDatasourceListener) {
                            ((LazyCollectionDatasourceListener) listener).completelyLoaded(this);
                        }
                    }
                }
            }
        }

        State prevState = state;
        if (!prevState.equals(State.VALID)) {
            state = State.VALID;
            forceStateChanged(prevState);
        }

        sw.stop();
    }

    public synchronized void sort(SortInfo[] sortInfos) {
        if (sortInfos.length != 1)
            throw new UnsupportedOperationException("Supporting sort by one field only");

        if (!Arrays.equals(this.sortInfos, sortInfos)) {
            this.sortInfos = sortInfos;
            doSort();
        }
    }

    private void doSort() {
        if (isCompletelyLoaded()) {
            sortInMemory();
        } else {
            for (Object entity : data.values()) {
                detachListener((Instance) entity);
            }
            data.clear();
            loadNextChunk(false);
        }
    }

    private void sortInMemory() {
        List<T> order = new ArrayList<T>(data.values());
        Collections.sort(order, createEntityComparator());
        data.clear();
        for (T t : order) {
            data.put(t.getId(), t);
        }
    }

    private void checkState() {
        if (!State.VALID.equals(state)) {
            refresh();
        }
    }

    @Override
    public synchronized void commited(Map<Entity, Entity> map) {
        if (map.containsKey(item)) {
            item = (T) map.get(item);
        }
        for (Entity newEntity : map.values()) {
            updateItem((T) newEntity);
        }

        modified = false;
        clearCommitLists();
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(boolean suspended) {
        boolean wasSuspended = this.suspended;
        this.suspended = suspended;
        if (wasSuspended && !suspended && refreshOnResumeRequired) {
            refresh();
        }
        refreshOnResumeRequired = false;
    }
}
