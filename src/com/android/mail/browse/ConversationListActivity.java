/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mail.browse;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemSelectedListener;

import com.android.mail.R;
import com.android.mail.ViewMode;
import com.android.mail.compose.ComposeActivity;
import com.android.mail.providers.Account;
import com.android.mail.providers.AccountCacheProvider;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.UIProvider;
import com.android.mail.browse.ConversationSelectionSet.ConversationSetObserver;

public class ConversationListActivity extends Activity implements OnItemSelectedListener,
        OnItemClickListener, ConversationSetObserver {

    private ListView mListView;
    private ConversationItemAdapter mListAdapter;
    private Spinner mAccountsSpinner;
    private AccountsSpinnerAdapter mAccountsAdapter;
    private ContentResolver mResolver;
    private Account mSelectedAccount;
    /** The selected conversations. */
    protected ConversationSelectionSet mBatchConversations = new ConversationSelectionSet();
    private SelectedConversationsActionMenu mSelectedConversationsActionMenu;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.conversation_list_activity);
        mListView = (ListView) findViewById(R.id.browse_list);
        mListView.setOnItemClickListener(this);
        mAccountsSpinner = (Spinner) findViewById(R.id.accounts_spinner);
        mResolver = getContentResolver();
        Cursor cursor = mResolver.query(AccountCacheProvider.getAccountsUri(),
                UIProvider.ACCOUNTS_PROJECTION, null, null, null);
        mAccountsAdapter = new AccountsSpinnerAdapter(this, cursor);
        mAccountsSpinner.setAdapter(mAccountsAdapter);
        mAccountsSpinner.setOnItemSelectedListener(this);
        mBatchConversations.addObserver(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.conversation_list_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean handled = true;
        int id = item.getItemId();
        switch (id) {
            case R.id.compose:
                ComposeActivity.compose(this, mSelectedAccount);
                break;
            default:
                handled = false;
                break;
        }
        return handled;
    }

    class AccountsSpinnerAdapter extends SimpleCursorAdapter implements SpinnerAdapter {

        private LayoutInflater mLayoutInflater;

        public AccountsSpinnerAdapter(Context context, Cursor cursor) {
            super(context, android.R.layout.simple_dropdown_item_1line, cursor,
                    UIProvider.ACCOUNTS_PROJECTION, null, 0);
            mLayoutInflater = LayoutInflater.from(context);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return getView(position, convertView, parent);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return mLayoutInflater.inflate(android.R.layout.simple_dropdown_item_1line, null);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            int accountNameCol = cursor.getColumnIndex(UIProvider.AccountColumns.NAME);
            ((TextView) view.findViewById(android.R.id.text1)).setText(cursor
                    .getString(accountNameCol));
        }
    }

    class ConversationItemAdapter extends SimpleCursorAdapter {

        public ConversationItemAdapter(Context context, int textViewResourceId,
                ConversationCursor cursor) {
            // Set requery/observer flags temporarily; we will be using loaders eventually so
            // this is just a temporary hack to demonstrate push, etc.
            super(context, textViewResourceId, cursor, UIProvider.CONVERSATION_PROJECTION, null,
                    CursorAdapter.FLAG_AUTO_REQUERY | CursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER);
            // UpdateCachingCursor needs to know about the adapter
            cursor.setAdapter(this);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            ConversationItemView view = new ConversationItemView(context, mSelectedAccount.name);
            return view;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            ((ConversationItemView) view).bind(cursor, null, mSelectedAccount.name, null,
                    new ViewMode(ConversationListActivity.this), mBatchConversations);
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        // Get an account and a folder list
        Uri foldersUri = null;
        Cursor cursor = mAccountsAdapter.getCursor();
        if (cursor != null && cursor.moveToPosition(position)) {
            int uriCol = cursor.getColumnIndex(UIProvider.AccountColumns.FOLDER_LIST_URI);
            foldersUri = Uri.parse(cursor.getString(uriCol));
            mSelectedAccount =  new Account(cursor);
            cursor.close();
        }
        Uri conversationListUri = null;
        if (foldersUri != null) {
            cursor = mResolver.query(foldersUri, UIProvider.FOLDERS_PROJECTION, null, null, null);
            if (cursor != null) {
                int uriCol = cursor.getColumnIndex(UIProvider.FolderColumns.CONVERSATION_LIST_URI);
                cursor.moveToFirst();
                conversationListUri = Uri.parse(cursor.getString(uriCol));
                cursor.close();
            }
        }
        // We need to have a conversation list here...
        if (conversationListUri == null) {
            throw new IllegalStateException("No conversation list for this account");
        }
        // Create the cursor for the list using the update cache
        ConversationCursor conversationListCursor =
                new ConversationCursor(
                    mResolver.query(conversationListUri, UIProvider.CONVERSATION_PROJECTION, null,
                            null, null), this, UIProvider.ConversationColumns.MESSAGE_LIST_URI);
        mListAdapter = new ConversationItemAdapter(this, R.layout.conversation_item_view_normal,
                conversationListCursor);
        mListView.setAdapter(mListAdapter);
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Conversation conv = ((ConversationItemView) view).getConversation();
        ConversationViewActivity.viewConversation(this, conv, mSelectedAccount);
    }

    @Override
    public void onSetEmpty(ConversationSelectionSet set) {
        mSelectedConversationsActionMenu = null;
    }

    @Override
    public void onSetBecomeUnempty(ConversationSelectionSet set) {
        mSelectedConversationsActionMenu = new SelectedConversationsActionMenu(this,
                mBatchConversations);
        mSelectedConversationsActionMenu.activate();
    }

    @Override
    public void onSetChanged(ConversationSelectionSet set) {
        // Do nothing.
    }
}
