package net.sitecore.android.sdk.widget;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.DialogFragment;
import android.content.ContentProviderOperation;
import android.content.Loader;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;

import java.util.LinkedList;
import java.util.List;

import net.sitecore.android.sdk.api.R;
import net.sitecore.android.sdk.api.ScApiSession;
import net.sitecore.android.sdk.api.ScRequest;
import net.sitecore.android.sdk.api.model.ItemsResponse;
import net.sitecore.android.sdk.api.model.RequestScope;
import net.sitecore.android.sdk.api.model.ScItem;
import net.sitecore.android.sdk.api.model.ScItemsLoader;

import static android.app.LoaderManager.LoaderCallbacks;
import static android.view.View.OnClickListener;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.sitecore.android.sdk.api.LogUtils.LOGD;
import static net.sitecore.android.sdk.api.provider.ScItemsContract.Items;

/**
 * Items browser fragment.
 */
@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
public class ItemsBrowserFragment extends DialogFragment {

    public static final String DEFAULT_ROOT_FOLDER = "/sitecore/content/Home";
    public static final int DEFAULT_GRID_COLUMNS_COUNT = 2;

    private static final String EXTRA_ITEM_ID = "item_id";

    private static final String SAVED_ITEMS = "items";
    private static final String SAVED_ROOT_FOLDER = "root_folder";

    private static final int STYLE_LIST = 0;
    private static final int STYLE_GRID = 1;

    private static final int LOADER_CHILD_ITEMS = 0;
    private static final int LOADER_ROOT_ITEM = 1;

    /**
     * Defines navigation callback methods.
     */
    public interface ContentChangedListener {

        /**
         * Notifies that
         *
         * @param item Current {@link ScItem} after event finished.
         */
        public void onGoUp(ScItem item);

        /**
         * @param item Current {@link ScItem} after event finished.
         */
        public void onGoInside(ScItem item);

        /**
         * @param item Current {@link ScItem} after initialization.
         */
        public void onInitialized(ScItem item);
    }

    /**
     * Defines network events callback methods.
     */
    public interface NetworkEventsListener {

        /**
         * Notifies that
         */
        public void onUpdateRequestStarted();

        /**
         * @param itemsResponse
         */
        public void onUpdateSuccess(ItemsResponse itemsResponse);

        /**
         * @param error
         */
        public void onUpdateError(VolleyError error);
    }

    private static final ContentChangedListener sEmptyContentChangedListener = new ContentChangedListener() {
        @Override
        public void onGoUp(ScItem item) {
        }

        @Override
        public void onGoInside(ScItem item) {
        }

        @Override
        public void onInitialized(ScItem item) {
        }
    };

    private static final NetworkEventsListener sEmptyNetworkEventsListener = new NetworkEventsListener() {
        @Override
        public void onUpdateRequestStarted() {
        }

        @Override
        public void onUpdateSuccess(ItemsResponse itemsResponse) {
        }

        @Override
        public void onUpdateError(VolleyError error) {
        }
    };

    private ContentChangedListener mNavigationEventsListener = sEmptyContentChangedListener;
    private NetworkEventsListener mNetworkEventsListener = sEmptyNetworkEventsListener;

    private View mContainerProgress;
    private LinearLayout mContainerList;
    private View mGoUpView;
    private AbsListView mListView;

    private ScItemsAdapter mAdapter;
    private ItemViewBinder mItemViewBinder = new DefaultItemViewBinder();

    private RequestQueue mRequestQueue;
    private ScApiSession mApiSession;

    private int mStyle = STYLE_LIST;
    private int mColumnCount = 2;
    private String mRootFolder = DEFAULT_ROOT_FOLDER;

    private LinkedList<ScItem> mItems = new LinkedList<ScItem>();

    private boolean mIsLoading = true;

    private final AdapterView.OnItemClickListener mOnItemClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            onScItemClick(mAdapter.getItem(position));
        }
    };

    private final AdapterView.OnItemLongClickListener mOnItemLongClickListener = new AdapterView.OnItemLongClickListener() {
        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
            onScItemLongClick(mAdapter.getItem(position));
            return true;
        }
    };

    private final OnClickListener mOnUpClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            LOGD("UP clicked in: " + mItems.peek().getId());
            mItems.pop();

            final ScItem newCurrentItem = mItems.peek();
            String newCurrentItemId = newCurrentItem.getId();
            LOGD("New folder item id: " + newCurrentItemId);

            sendUpdateChildrenRequest(newCurrentItemId);
            reloadChildrenFromDatabase(newCurrentItemId);

            if (mItems.size() == 1) {
                mGoUpView.setVisibility(View.GONE);
            }
            mNavigationEventsListener.onGoUp(newCurrentItem);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            // TODO: load saved state
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // TODO: save items list, mApiSession(?)
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // TODO: replace inflation with code
        final View v = inflater.inflate(R.layout.fragment_items_browser, container, false);
        return v;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        final LayoutInflater inflater = LayoutInflater.from(getActivity());

        mContainerProgress = view.findViewById(R.id.container_progress);
        mContainerList = (LinearLayout) view.findViewById(R.id.container_list);

        // Add header view if exists
        final View header = onCreateHeaderView(inflater);
        if (header != null) {
            mContainerList.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }

        // Add up button
        mGoUpView = onCreateUpButtonView(inflater);
        mGoUpView.setVisibility(View.GONE);
        mGoUpView.setOnClickListener(mOnUpClickListener);
        mContainerList.addView(mGoUpView, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        // Add ListView/GridView
        if (mStyle == STYLE_LIST) {
            mListView = new ListView(getActivity());
        } else {
            GridView grid = new GridView(getActivity());
            grid.setNumColumns(mColumnCount);
            mListView = grid;
        }

        mListView.setDrawSelectorOnTop(false);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        listParams.weight = 1;
        mContainerList.addView(mListView, listParams);

        final View empty = onCreateEmptyView(inflater);
        empty.setVisibility(View.GONE);
        final LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        emptyParams.weight = 1;
        mContainerList.addView(empty, emptyParams);
        mListView.setEmptyView(empty);

        // Add footer view if exists
        final View footer = onCreateFooterView(inflater);
        if (footer != null) {
            mContainerList.addView(footer, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }

        // Set callbacks
        mListView.setOnItemClickListener(mOnItemClickListener);
        mListView.setOnItemLongClickListener(mOnItemLongClickListener);
    }

    protected View onCreateHeaderView(LayoutInflater inflater) {
        return null;
    }

    protected View onCreateFooterView(LayoutInflater inflater) {
        return null;
    }

    /**
     * Creates view, intended for Up navigation through items tree. This view will be added above items browser list.
     * After creation {@link OnClickListener} will be set to created view, which triggers navigation up.
     *
     * @param inflater LayoutInflater object.
     *
     * @return View, intended for Up navigation through items tree. After creation will have
     */
    protected View onCreateUpButtonView(LayoutInflater inflater) {
        final Button upButton = new Button(getActivity());
        upButton.setText("..");
        return upButton;
    }

    /**
     * @param inflater
     *
     * @return
     */
    protected View onCreateEmptyView(LayoutInflater inflater) {
        final TextView empty = new TextView(getActivity());
        empty.setText("Empty");
        empty.setGravity(Gravity.CENTER);
        return empty;
    }

    /**
     * Override this method to change the way ListItem views are created from {@link ScItem}.
     *
     * @return {@link ItemViewBinder}
     */
    protected ItemViewBinder onCreateItemViewBinder() {
        return mItemViewBinder;
    }

    @Override
    public void onInflate(Activity activity, AttributeSet attrs, Bundle savedInstanceState) {
        super.onInflate(activity, attrs, savedInstanceState);
        TypedArray a = activity.obtainStyledAttributes(attrs, R.styleable.ItemsBrowserFragment);

        mStyle = a.getInt(R.styleable.ItemsBrowserFragment_style, STYLE_LIST);
        mColumnCount = a.getInt(R.styleable.ItemsBrowserFragment_columnCount, DEFAULT_GRID_COLUMNS_COUNT);
        String root = a.getString(R.styleable.ItemsBrowserFragment_rootFolder);
        if (!TextUtils.isEmpty(root)) mRootFolder = root;

        a.recycle();
    }

    private void setLoading(boolean isLoading) {
        mIsLoading = isLoading;
        mContainerProgress.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        mContainerList.setVisibility(isLoading ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onDetach() {
        if (mRequestQueue != null) {
            mRequestQueue.cancelAll(this);
        }
        super.onDetach();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (mApiSession != null && mRequestQueue != null) {
            loadContent();
        }
    }

    /**
     * Sets {@link ScApiSession} to create the requests.
     */
    public void setApiSession(ScApiSession session) {
        mApiSession = session;
        mApiSession.setShouldCache(true);
    }

    /**
     * Sets {@link RequestQueue} which will execute the requests.
     */
    public void setRequestQueue(RequestQueue requestQueue) {
        mRequestQueue = requestQueue;
    }

    /**
     *
     */
    public void loadContent() {
        mNetworkEventsListener.onUpdateRequestStarted();
        ScRequest request = mApiSession.getItems(mFirstItemResponseListener, mErrorListener)
                .withScope(RequestScope.SELF, RequestScope.CHILDREN)
                .byItemPath(mRootFolder)
                .build();

        request.setTag(ItemsBrowserFragment.this);

        mRequestQueue.add(request);
        setLoading(true);
    }

    /**
     *
     */
    public void loadContentWithoutApiSession() {
    }

    /**
     * @param rootFolder
     */
    public void setRootFolder(String rootFolder) {
        mRootFolder = rootFolder;
    }

    public void update() {
        final ScItem item = mItems.peek();
        sendUpdateChildrenRequest(item.getId());
    }

    private void reloadChildrenFromDatabase(String itemId) {
        LOGD("Reload db children of: " + itemId);
        final Bundle bundle = new Bundle();
        bundle.putString(EXTRA_ITEM_ID, itemId);
        getLoaderManager().restartLoader(LOADER_CHILD_ITEMS, bundle, mLoaderCallbacks);
    }

    private void sendUpdateChildrenRequest(String itemId) {
        LOGD("getChildren: " + itemId);
        if (mApiSession != null) {
            mNetworkEventsListener.onUpdateRequestStarted();
            ScRequest request = mApiSession.getItems(mItemsResponseListener, mErrorListener)
                    .byItemId(itemId)
                    .withScope(RequestScope.CHILDREN)
                    .build();
            request.setTag(ItemsBrowserFragment.this);

            ContentProviderOperation.Builder builder = ContentProviderOperation.newDelete(Items.CONTENT_URI);
            builder.withSelection(Items.Query.BY_ITEM_PARENT_ID, new String[]{itemId});
            request.addOperationBeforeSuccessfulResponseSaved(builder.build());

            mRequestQueue.add(request);
        }
    }

    private final Response.Listener<ItemsResponse> mFirstItemResponseListener = new Response.Listener<ItemsResponse>() {
        @Override
        public void onResponse(ItemsResponse itemsResponse) {
            if (itemsResponse.getItems().size() == 0) {
                // TODO: handle empty root view;
                return;
            }
            setLoading(false);
            ScItem item = itemsResponse.getItems().get(0);
            mItems.push(item);

            mNavigationEventsListener.onInitialized(item);
            mNetworkEventsListener.onUpdateSuccess(itemsResponse);

            final Bundle bundle = new Bundle();
            bundle.putString(EXTRA_ITEM_ID, item.getId());
            getLoaderManager().initLoader(LOADER_CHILD_ITEMS, bundle, mLoaderCallbacks);
        }
    };

    private final Response.Listener<ItemsResponse> mItemsResponseListener = new Response.Listener<ItemsResponse>() {
        @Override
        public void onResponse(ItemsResponse itemsResponse) {
            mNetworkEventsListener.onUpdateSuccess(itemsResponse);
        }
    };

    private Response.ErrorListener mErrorListener = new Response.ErrorListener() {
        @Override
        public void onErrorResponse(VolleyError error) {
            setLoading(false);
            mNetworkEventsListener.onUpdateError(error);
        }
    };

    private final LoaderCallbacks<List<ScItem>> mLoaderCallbacks = new LoaderCallbacks<List<ScItem>>() {

        @Override
        public Loader<List<ScItem>> onCreateLoader(int id, Bundle args) {
            if (args == null) return new ScItemsLoader(getActivity(), null, null);

            final String currentItemId = args.getString(EXTRA_ITEM_ID);
            return new ScItemsLoader(getActivity(), Items.Query.BY_ITEM_PARENT_ID, new String[]{currentItemId});
        }

        @Override
        public void onLoadFinished(Loader<List<ScItem>> loader, List<ScItem> data) {
            mAdapter = new ScItemsAdapter(getActivity(), data, onCreateItemViewBinder());
            mListView.setAdapter(mAdapter);
        }

        @Override
        public void onLoaderReset(Loader<List<ScItem>> loader) {
            mAdapter.clear();
        }
    };

    public void setListStyle() {
        mStyle = STYLE_LIST;
    }

    public void setGridStyle(int columnCount) {
        mStyle = STYLE_GRID;
        mColumnCount = columnCount;
    }

    /**
     * @return Current item or null if data wasn't loaded.
     */
    public ScItem getCurrentItem() {
        return mItems.peek();
    }

    /**
     * @param item which received click event.
     */
    public void onScItemClick(ScItem item) {
        LOGD("New folder item id: " + item.getId());
        mItems.push(item);

        if (mGoUpView.getVisibility() == View.GONE) mGoUpView.setVisibility(View.VISIBLE);

        String itemId = item.getId();
        sendUpdateChildrenRequest(itemId);
        reloadChildrenFromDatabase(itemId);
        mNavigationEventsListener.onGoInside(item);
    }

    /**
     * @param item which received long click.
     */
    public void onScItemLongClick(ScItem item) {
    }

    /**
     * Register a callback to be invoked when content state changes.
     *
     * @param navigationEventsListener
     */
    public void setContentEventsListener(ContentChangedListener navigationEventsListener) {
        mNavigationEventsListener = navigationEventsListener;
    }

    /**
     * Register a callback to be invoked when network operations state changes.
     *
     * @param networkEventsListener
     */
    public void setNetworkEventsListener(NetworkEventsListener networkEventsListener) {
        mNetworkEventsListener = networkEventsListener;
    }
}