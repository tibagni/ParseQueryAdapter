package com.parse;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.widget.Adapter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import bolts.CancellationTokenSource;

public abstract class ParseQueryAdapter<T extends ParseObject, U extends RecyclerView.ViewHolder>
        extends RecyclerView.Adapter<U> {

    /**
     * Implement to construct your own custom {@link ParseQuery} for fetching objects.
     */
    public interface QueryFactory<T extends ParseObject> {
        ParseQuery<T> create();
    }

    /**
     * Implement with logic that is called before and after objects are fetched from Parse by the
     * adapter.
     */
    public interface OnQueryLoadListener<T extends ParseObject> {
        void onLoading(boolean isFirstPage);
        void onCanceled();
        void onLoaded(List<T> objects, ParseException e);
    }

    private final Object mLock = new Object();
    private ParseQueryPager<T> mPager;
    private CancellationTokenSource mCancelTokenSource;

    private ParseQuery<T> mQuery;
    private int mObjectsPerPage = 20;

    private ParseQuery.CachePolicy mPrevCachePolicy;

    // A WeakHashMap, keeping track of the DataSetObservers on this class
    private WeakHashMap<RecyclerView.AdapterDataObserver, Void> mDataSetObservers =
            new WeakHashMap<>();

    // Whether the adapter should trigger loadObjects() on registerDataSetObserver(); Defaults to
    // true.
    private boolean mIsAutoLoad = true;

    private Context mContext;

    private Set<OnQueryLoadListener<T>> mOnQueryLoadListeners = new HashSet<>();

    /**
     * Constructs a {@code ParseQueryAdapter}. Given a class name, this adapter
     * will fetch and display all {@link ParseObject}s of the specified class, ordered by creation
     * time.
     *
     * @param context
     *          The activity utilizing this adapter.
     * @param className
     *          The name of the Parse class of {@link ParseObject}s to display.
     */
    public ParseQueryAdapter(Context context, final String className) {
        this(context, new QueryFactory<T>() {
            @Override
            public ParseQuery<T> create() {
                ParseQuery<T> query = ParseQuery.getQuery(className);
                query.orderByDescending("createdAt");

                return query;
            }
        });

        if (className == null) {
            throw new RuntimeException("You need to specify a className for the ParseQueryAdapter");
        }
    }
    /**
     * Constructs a {@code ParseQueryAdapter}. Allows the caller to define further constraints on
     * the {@link ParseQuery} to be used when fetching items from Parse.
     *
     * @param context
     *          The activity utilizing this adapter.
     * @param queryFactory
     *          A {@link QueryFactory} to build a {@link ParseQuery} for fetching objects.
     */
    public ParseQueryAdapter(Context context, QueryFactory<T> queryFactory) {
        super();
        mContext = context;
        mQuery = queryFactory.create();
    }

    /**
     * Return the context provided by the {@code Activity} utilizing this {@code ParseQueryAdapter}.
     *
     * @return The activity utilizing this adapter.
     */
    public Context getContext() {
        return mContext;
    }

    private ParseQueryPager<T> getPager() {
        synchronized (mLock) {
            if (mPager == null) {
                mPager = new ParseQueryPager<>(mQuery, mObjectsPerPage);
                mCancelTokenSource = new CancellationTokenSource();
            }
            return mPager;
        }
    }

    public void setQuery(QueryFactory<T> queryFactory) {
        mQuery = queryFactory.create();

        // Re-load the objects based on the new Query
        loadObjects();
    }

    private List<T> getObjects() {
        return getPager().getObjects();
    }

    private int getPaginationCellRow() {
        // Pagination cell row is the last element, after all objects from that page
        return getObjects().size();
    }

    public T getItem(int index) {
        if (index == getPaginationCellRow()) {
            return null;
        }
        return getObjects().get(index);
    }

    /**
     * Enable or disable the automatic loading of results upon attachment to an {@code AdapterView}.
     * Defaults to true.
     *
     * @param autoload
     *          Defaults to true.
     */
    public void setAutoload(boolean autoload) {
        if (mIsAutoLoad == autoload) {
            // An extra precaution to prevent an overzealous setAutoload(true) after assignment to
            // an AdapterView from triggering an unnecessary additional loadObjects().
            return;
        }
        mIsAutoLoad = autoload;
        if (mIsAutoLoad && !mDataSetObservers.isEmpty() && getObjects().isEmpty()) {
            loadObjects();
        }
    }

    /**
     * Clear the cache results to force an updated query next time. This method only makes sense
     * if the query provided has a cache policy.
     */
    public void forceUpdate() {
        if(mQuery.getCachePolicy() != ParseQuery.CachePolicy.IGNORE_CACHE) {
            // For some reason, clearCachedResult does not work as expected and, even after calling it
            // Query gets the results from the Cache. In order to workaround this SHIT, we set the Cache
            // policy here to NETWORK_ELSE_CACHE and then, after the objects are loaded, we set it back
            // to what it was.
            //mQuery.clearCachedResult();
            mPrevCachePolicy = mQuery.getCachePolicy();
            mQuery.setCachePolicy(ParseQuery.CachePolicy.NETWORK_ELSE_CACHE);
        }
    }

    /**
     * Clears the table and loads the first page of objects asynchronously. This method is called
     * automatically when this {@code Adapter} is attached to an {@code AdapterView}.
     * <p/>
     * {@code loadObjects()} should only need to be called if {@link #setAutoload(boolean)} is set
     * to {@code false}.
     */
    public void loadObjects() {
        loadNextPage(true);
    }

    private void loadNextPage(final boolean shouldClear) {
        synchronized (mLock) {
            if (shouldClear && mPager != null) {
                mCancelTokenSource.cancel();
                mPager = null;
            }
        }

        notifyOnLoadingListeners(mPager == null || mPager.getCurrentPage() < 0);

        getPager().loadNextPage(new FindCallback<T>() {
            @Override
            public void done(List<T> results, ParseException e) {
                if (results == null && e == null) {
                    // Cancelled
                    notifyOnCanceledListeners();
                } else {

                    // Backwards compatibility
                    if ((!Parse.isLocalDatastoreEnabled() &&
                            mQuery.getCachePolicy() == ParseQuery.CachePolicy.CACHE_ONLY) &&
                            (e != null) && e.getCode() == ParseException.CACHE_MISS) {
                        // no-op on cache miss
                        return;
                    }

                    // Reset the cache policy to what it were if we are force updating
                    if (mPrevCachePolicy != null) {
                        mQuery.setCachePolicy(mPrevCachePolicy);
                        mPrevCachePolicy = null;
                    }

                    // Loaded
                    notifyDataSetChanged();
                    notifyOnLoadedListeners(results, e);
                }

            }
        }, mCancelTokenSource.getToken());
    }

    /**
     * Loads the next page of objects, appends to table, and notifies the UI that the model has
     * changed.
     */
    public void loadNextPage() {
        if (getPager().hasNextPage()) {
            loadNextPage(false);
        }
    }

    /**
     * Remove all elements from the list.
     */
    public void clear() {
        synchronized (mLock) {
            if (mCancelTokenSource != null) {
                mCancelTokenSource.cancel();
            }
            mPager = null;
            mCancelTokenSource = null;
        }

        notifyDataSetChanged();
    }

    public void setObjectsPerPage(int objectsPerPage) {
        mObjectsPerPage = objectsPerPage;
    }

    public int getObjectsPerPage() {
        return mObjectsPerPage;
    }

    public void addOnQueryLoadListener(OnQueryLoadListener<T> listener) {
        mOnQueryLoadListeners.add(listener);
    }

    public void removeOnQueryLoadListener(OnQueryLoadListener<T> listener) {
        mOnQueryLoadListeners.remove(listener);
    }

    private void notifyOnCanceledListeners() {
        for (OnQueryLoadListener<T> listener : mOnQueryLoadListeners) {
            listener.onCanceled();
        }
    }

    private void notifyOnLoadingListeners(boolean isFirstPage) {
        for (OnQueryLoadListener<T> listener : mOnQueryLoadListeners) {
            listener.onLoading(isFirstPage);
        }
    }

    private void notifyOnLoadedListeners(List<T> objects, ParseException e) {
        for (OnQueryLoadListener<T> listener : mOnQueryLoadListeners) {
            listener.onLoaded(objects, e);
        }
    }

    /**
     * Overrides {@link Adapter#getCount()} method to return the number of cells to
     * display. If pagination is turned on, this count will include an extra +1 count for the
     * pagination cell row.
     *
     * @return The number of cells to be displayed by the {@link android.widget.ListView}.
     */
    @Override
    public int getItemCount() {
        return getObjects().size();
    }

    /** {@inheritDoc} **/
    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public void registerAdapterDataObserver(RecyclerView.AdapterDataObserver observer) {
        super.registerAdapterDataObserver(observer);
        mDataSetObservers.put(observer, null);
        if (mIsAutoLoad) {
            loadObjects();
        }
    }

    @Override
    public void unregisterAdapterDataObserver(RecyclerView.AdapterDataObserver observer) {
        super.unregisterAdapterDataObserver(observer);
        mDataSetObservers.remove(observer);
    }
}