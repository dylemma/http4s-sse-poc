import '../styles/index.scss'

import 'core-js/stable' // introduces polyfills for standard classes to make IE11 work

import React from 'react'
import ReactDOM from 'react-dom'
import sharedLiveUpdates from './sharedLiveUpdates'

function BroadcastChannelDemo() {
	const [state, setState] = React.useState(null)
	React.useEffect(() => {
		return sharedLiveUpdates.onValue(e => setState(e))
	}, [setState])

	return state && JSON.stringify(state)
}

ReactDOM.render(
	<BroadcastChannelDemo />,
	document.getElementById('demo-container')
)
