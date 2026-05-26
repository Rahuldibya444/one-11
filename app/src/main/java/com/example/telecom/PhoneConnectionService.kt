package com.example.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.util.Log

class PhoneConnectionService : ConnectionService() {
    private val TAG = "PhoneX_ConnService"

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateOutgoingConnection requested")
        val connection = PhoneConnection()
        connection.onStateChanged(Connection.STATE_ACTIVE)
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateIncomingConnection requested")
        val connection = PhoneConnection()
        connection.onStateChanged(Connection.STATE_ACTIVE)
        return connection
    }
}

class PhoneConnection : Connection() {
    init {
        setInitializing()
        setActive()
    }
}
