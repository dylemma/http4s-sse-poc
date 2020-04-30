import '../styles/index.scss'
import BroadcastChannel from 'broadcast-channel'
import React from 'react'
import ReactDOM from 'react-dom'
import TabElect from 'tab-elect'
import { NativeEventSource, EventSourcePolyfill } from 'event-source-polyfill'
import createLeaderElection from 'broadcast-channel'
const EventSource = NativeEventSource || EventSourcePolyfill
console.log('EventSource:', EventSource);


function LiveUpdateDemo() {
	const [state, setState] = React.useState(null)
	React.useEffect(() => {
		const events = new EventSource('/api/sse')
		events.addEventListener('counter', e => {
			// console.log('event:', e)
			const { data, lastEventId, type } = e
			setState({ data, lastEventId, type })
		})
		return () => events.close()
	}, [setState])

	if(state === null) {
		return null
	} else {
		return JSON.stringify(state)
	}
}

ReactDOM.render(
	<LiveUpdateDemo />,
	document.getElementById('live-update-demo')
)

function TabElectDemo() {
	const [isLeader, setLeader] = React.useState(false)
	React.useEffect(() => {
		const te = TabElect("demo")
		setLeader(te.isLeader)
		te.on('elected', () => setLeader(true))
		te.on('deposed', () => setLeader(false))

		return () => {
			te.destroy()
			setLeader(false)
		}
	}, [setLeader])

	return isLeader ? 'Leader Tab!' : 'Follower tab'
}

ReactDOM.render(
	<TabElectDemo />,
	document.getElementById('tab-elect-demo')
)