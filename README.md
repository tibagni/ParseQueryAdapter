# ParseQueryAdapter
An Implementation of the ParseQueryAdapter to be used with RecyclerViews. It supports Cache and pagination.


### How to implement pagination on scroll
```java
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                //check for scroll down
                if (dy > 0) {
                    int visibleItemCount = mLayoutManager.getChildCount();
                    int totalItemCount = mLayoutManager.getItemCount();
                    int pastVisibleItems = mLayoutManager.findFirstVisibleItemPosition();

                    if ((visibleItemCount + pastVisibleItems) >= totalItemCount) {
                        // If the adapter is empty, it makes no sense to fetch the next page!!!
                        if (mAdapter.getItemCount() > 0) {
                            mAdapter.loadNextPage();
                        }
                    }
                }
            }
        });
```
