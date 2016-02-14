package com.parse;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import bolts.CancellationToken;
import bolts.Continuation;
import bolts.Task;
import bolts.TaskCompletionSource;

/**
 * A utility class to page through {@link ParseQuery} results.
 *
 * @param <T>
 */
public class ParseQueryPager<T extends ParseObject> {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String TAG = "ParseQueryPager";

    /**
     * The callback that is called by {@link ParseQueryPager} when the results have changed.
     *
     * @param <T>
     */
    public interface OnObjectsChangedCallback<T extends ParseQueryPager> {
        /**
         * Called whenever a change of unknown type has occurred, such as the entire list being set
         * to new values.
         *
         * @param sender The changing pager.
         */
        void onChanged(T sender);

        /**
         * Called whenever one or more items have changed.
         *
         * @param sender        The changing pager.
         * @param positionStart The starting index that has changed.
         * @param itemCount     The number of items that have been changed.
         */
        void onItemRangeChanged(T sender, int positionStart, int itemCount);

        /**
         * Called whenever one or more items have been inserted into the result set.
         *
         * @param sender        The changing pager.
         * @param positionStart The starting index that has been inserted.
         * @param itemCount     The number of items that have been inserted.
         */
        void onItemRangeInserted(T sender, int positionStart, int itemCount);

        /**
         * Called whenever one or more items have been moved from the result set.
         *
         * @param sender       The changing pager.
         * @param fromPosition The position from which the items were moved.
         * @param toPosition   The destination position of the items.
         * @param itemCount    The number of items that have been inserted.
         */
        void onItemRangeMoved(T sender, int fromPosition, int toPosition, int itemCount);

        /**
         * Called whenever one or more items have been removed from the result set.
         *
         * @param sender        The changing pager.
         * @param positionStart The starting index that has been inserted.
         * @param itemCount     The number of items that have been inserted.
         */
        void onItemRangeRemoved(T sender, int positionStart, int itemCount);
    }

    private final ParseQuery<T> mQuery;
    private final int mPageSize;
    private final List<T> mObjects = new ArrayList<>();
    private final List<T> mUnmodifiableObjects = Collections.unmodifiableList(mObjects);
    private final Set<OnObjectsChangedCallback> mCallbacks = new HashSet<>();
    private final Object mLock = new Object();

    private int mCurrentPage = -1;
    private boolean mHasNextPage = true;
    private Task<List<T>> mLoadNextPageTask;

    /**
     * Constructs a new instance of {@code ParseQueryPager} with the specified mQuery.
     *
     * @param query The mQuery for this {@code ParseQueryPager}.
     */
    public ParseQueryPager(ParseQuery<T> query) {
        this(query, DEFAULT_PAGE_SIZE);
    }

    /**
     * Constructs a new instance of {@code ParseQueryPager} with the specified mQuery.
     *
     * @param query    The mQuery for this {@code ParseQueryPager}.
     * @param pageSize The size of each page.
     */
    public ParseQueryPager(ParseQuery<T> query, int pageSize) {
        this.mQuery = new ParseQuery<>(query);
        this.mPageSize = pageSize;
    }

    /**
     * @return the mQuery for this {@code ParseQueryPager}.
     */
    public ParseQuery<T> getQuery() {
        return mQuery;
    }

    /**
     * @return the size of each page for this {@code ParseQueryPager}.
     */
    public int getPageSize() {
        return mPageSize;
    }

    /**
     * Returns the current page of the pager in the result set.
     * <p/>
     * The value is zero-based. When the row set is first returned the pager will be at position -1,
     * which is before the first page.
     *
     * @return the current page.
     */
    public int getCurrentPage() {
        synchronized (mLock) {
            return mCurrentPage;
        }
    }

    /**
     * @return whether the pager has more pages.
     */
    public boolean hasNextPage() {
        synchronized (mLock) {
            return mHasNextPage;
        }
    }

    /**
     * @return whether the pager is currently loading the next page.
     */
    public boolean isLoadingNextPage() {
        synchronized (mLock) {
            return mLoadNextPageTask != null && !mLoadNextPageTask.isCompleted();
        }
    }

    /**
     * @return the loaded objects.
     */
    public List<T> getObjects() {
        return mUnmodifiableObjects;
    }

    public void addOnObjectsChangedCallback(OnObjectsChangedCallback callback) {
        synchronized (mLock) {
            mCallbacks.add(callback);
        }
    }

    public void removeOnObjectsChangedCallback(OnObjectsChangedCallback callback) {
        synchronized (mLock) {
            mCallbacks.remove(callback);
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyRangeChanged(int positionStart, int positionEnd) {
        synchronized (mLock) {
            for (OnObjectsChangedCallback callback : mCallbacks) {
                callback.onItemRangeChanged(this, positionStart, positionEnd);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyRangeInserted(int positionStart, int positionEnd) {
        synchronized (mLock) {
            for (OnObjectsChangedCallback callback : mCallbacks) {
                callback.onItemRangeInserted(this, positionStart, positionEnd);
            }
        }
    }

    private void setLoadNextPageTask(Task<List<T>> task) {
        synchronized (mLock) {
            mLoadNextPageTask = task.continueWithTask(new Continuation<List<T>, Task<List<T>>>() {
                @Override
                public Task<List<T>> then(Task<List<T>> task) throws Exception {
                    synchronized (mLock) {
                        mLoadNextPageTask = null;
                    }
                    return task;
                }
            });
        }
    }

    /**
     * Returns a new instance of {@link ParseQuery} to be used to load the next page of results.
     * <p/>
     * Its limit should be one more than {@code mPageSize} so that {@code mHasNextPage} can be
     * determined.
     *
     * @param page The page the mQuery should load.
     * @return a new instance of {@link ParseQuery}.
     */
    protected ParseQuery<T> createQuery(int page) {
        ParseQuery<T> query = new ParseQuery<>(getQuery());
        query.setSkip(getPageSize() * page);
        // Limit is mPageSize + 1 so we can detect if there are more pages
        query.setLimit(getPageSize() + 1);
        return query;
    }

    /**
     * Loads the next page.
     * <p/>
     * The next page is defined by {@code mCurrentPage + 1}.
     *
     * @param callback A {@code callback} that will be called with the result of the next page.
     * @param ct       Token used to cancel the task.
     */
    public void loadNextPage(final FindCallback<T> callback, final CancellationToken ct) {
        if (!hasNextPage()) {
            throw new IllegalStateException("Unable to load next page when there are no more " +
                    "pages available");
        }

        final int page = getCurrentPage() + 1;

        final TaskCompletionSource<List<T>> tcs = new TaskCompletionSource<>();
        final ParseQuery<T> query = createQuery(page);

        ParseQuery.CachePolicy policy = query.getCachePolicy();
        if (policy == ParseQuery.CachePolicy.CACHE_THEN_NETWORK ||
                policy == ParseQuery.CachePolicy.CACHE_ELSE_NETWORK) {

            // If there is no cached results, don't waste time looking for it!
            if (!query.hasCachedResult()) {
                query.setCachePolicy(ParseQuery.CachePolicy.NETWORK_ONLY);
            }
        }

        query.findInBackground(new FindCallback<T>() {

            AtomicInteger callbacks = new AtomicInteger();

            @Override
            public void done(List<T> results, ParseException e) {
                boolean isCancelled = ct != null && ct.isCancellationRequested();
                if (!isCancelled && e == null) {
                    onPage(page, results);
                } else if (isCancelled) {
                    Log.d(TAG, "Load was canceled, set the results to null!");
                    results = null;
                }

                boolean isCacheThenNetwork = false;
                try {
                    ParseQuery.CachePolicy policy = query.getCachePolicy();
                    isCacheThenNetwork = policy == ParseQuery.CachePolicy.CACHE_THEN_NETWORK;
                } catch (IllegalStateException ex) {
                    // do nothing, LDS is enabled and we can't use CACHE_THEN_NETWORK
                }

                if (!isCacheThenNetwork || callbacks.incrementAndGet() >= 2) {
                    if (isCancelled) {
                        tcs.trySetCancelled();
                    } else {
                        tcs.trySetResult(results);
                    }
                }

                callback.done(results, e);
            }
        });

        setLoadNextPageTask(tcs.getTask());
    }

    private void onPage(int page, List<T> results) {
        synchronized (mLock) {
            int itemCount = results.size();

            mCurrentPage = page;

            // We detect if there are more pages by setting the limit mPageSize + 1 and we
            // remove the extra if there are more pages.
            mHasNextPage = itemCount >= mPageSize + 1;
            if (itemCount > mPageSize) {
                results.remove(mPageSize);
            }

            int objectsSize = mObjects.size();
            boolean inserted = true;
            if (objectsSize > mPageSize * page) {
                inserted = false;
                mObjects.subList(mPageSize * page, Math.min(objectsSize, mPageSize *
                        (page + 1))).clear();
            }
            mObjects.addAll(mPageSize * page, results);

            int positionStart = mPageSize * page;
            if (inserted) {
                notifyRangeInserted(positionStart, itemCount);
            } else {
                notifyRangeChanged(positionStart, itemCount);
            }
        }
    }
}