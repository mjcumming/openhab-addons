URL: https://github.com/Velleman/python-linkplay/blob/main/src/linkplay/discovery.py
---


You signed in with another tab or window. Reload to refresh your session.You signed out in another tab or window. Reload to refresh your session.You switched accounts on another tab or window. Reload to refresh your session.Dismiss alert

{{ message }}

[Velleman](/Velleman)/ **[python-linkplay](/Velleman/python-linkplay)** Public

- [Notifications](/login?return_to=%2FVelleman%2Fpython-linkplay) You must be signed in to change notification settings
- [Fork\\
6](/login?return_to=%2FVelleman%2Fpython-linkplay)
- [Star\\
10](/login?return_to=%2FVelleman%2Fpython-linkplay)


## Files

main

/

# discovery.py

Blame

Blame

## Latest commit

[![anderscarling](https://avatars.githubusercontent.com/u/49722?v=4&size=40)](/anderscarling)[anderscarling](/Velleman/python-linkplay/commits?author=anderscarling)

[Support devices running https on port 4443 (](/Velleman/python-linkplay/commit/5ce42756455113fd6f679f84a26e348f4e70be3a) [#67](https://github.com/Velleman/python-linkplay/pull/67) [)](/Velleman/python-linkplay/commit/5ce42756455113fd6f679f84a26e348f4e70be3a)

Dec 2, 2024

[5ce4275](/Velleman/python-linkplay/commit/5ce42756455113fd6f679f84a26e348f4e70be3a) · Dec 2, 2024

## History

[History](/Velleman/python-linkplay/commits/main/src/linkplay/discovery.py)

127 lines (100 loc) · 4.5 KB

/

# discovery.py

Top

## File metadata and controls

- Code

- Blame


127 lines (100 loc) · 4.5 KB

[Raw](https://github.com/Velleman/python-linkplay/raw/refs/heads/main/src/linkplay/discovery.py)

1

2

3

4

5

6

7

8

9

10

11

12

13

14

15

16

17

18

19

20

21

22

23

24

25

26

27

28

29

30

31

32

33

34

35

36

37

38

39

40

41

42

43

44

45

46

47

48

49

50

51

52

53

54

55

56

57

58

59

60

61

62

63

64

65

66

67

68

69

70

71

72

73

74

75

76

77

78

79

80

81

82

83

84

85

86

87

88

89

90

91

92

93

94

95

96

97

98

99

100

101

102

103

104

105

106

107

108

109

110

111

112

113

114

115

116

117

118

119

120

121

122

123

124

125

126

127

fromtypingimportAny

fromaiohttpimportClientSession

fromasync\_upnp\_client.searchimportasync\_search

fromasync\_upnp\_client.utilsimportCaseInsensitiveDict

fromdeprecatedimportdeprecated

fromlinkplay.bridgeimportLinkPlayBridge

fromlinkplay.constsimportUPNP\_DEVICE\_TYPE, LinkPlayCommand, MultiroomAttribute

fromlinkplay.endpointimportLinkPlayApiEndpoint, LinkPlayEndpoint

fromlinkplay.exceptionsimportLinkPlayInvalidDataException, LinkPlayRequestException

@deprecated(

reason="Use linkplay\_factory\_bridge\_endpoint with a LinkPlayEndpoint or linkplay\_factory\_httpapi\_bridge instead.",

version="0.0.7",

)

asyncdeflinkplay\_factory\_bridge(

ip\_address: str, session: ClientSession

) ->LinkPlayBridge\|None:

"""Attempts to create a LinkPlayBridge from the given IP address.

Returns None if the device is not an expected LinkPlay device."""

endpoint: LinkPlayApiEndpoint=LinkPlayApiEndpoint(

protocol="http", port=80, endpoint=ip\_address, session=session

)

try:

returnawaitlinkplay\_factory\_bridge\_endpoint(endpoint)

exceptLinkPlayRequestException:

returnNone

asyncdeflinkplay\_factory\_bridge\_endpoint(

endpoint: LinkPlayEndpoint,

) ->LinkPlayBridge:

"""Attempts to create a LinkPlayBridge from given LinkPlayEndpoint.

Raises LinkPlayRequestException if the device is not an expected LinkPlay device."""

bridge: LinkPlayBridge=LinkPlayBridge(endpoint=endpoint)

awaitbridge.device.update\_status()

awaitbridge.player.update\_status()

returnbridge

asyncdeflinkplay\_factory\_httpapi\_bridge(

ip\_address: str, session: ClientSession

) ->LinkPlayBridge:

"""Attempts to create a LinkPlayBridge from the given IP address.

Attempts to use HTTPS first, then falls back to HTTP.

Raises LinkPlayRequestException if the device is not an expected LinkPlay device."""

protocol\_port\_pairs= \[("https", 443), ("https", 4443)\]

forprotocol, portinprotocol\_port\_pairs:

endpoint: LinkPlayApiEndpoint=LinkPlayApiEndpoint(

protocol=protocol, port=port, endpoint=ip\_address, session=session

)

try:

returnawaitlinkplay\_factory\_bridge\_endpoint(endpoint)

exceptLinkPlayRequestException:

pass

http\_endpoint: LinkPlayApiEndpoint=LinkPlayApiEndpoint(

protocol="http", port=80, endpoint=ip\_address, session=session

)

returnawaitlinkplay\_factory\_bridge\_endpoint(http\_endpoint)

asyncdefdiscover\_linkplay\_bridges(

session: ClientSession, discovery\_through\_multiroom: bool=True

) ->list\[LinkPlayBridge\]:

"""Attempts to discover LinkPlay devices on the local network."""

bridges: dict\[str, LinkPlayBridge\] = {}

asyncdefadd\_linkplay\_device\_to\_list(upnp\_device: CaseInsensitiveDict):

ip\_address: str\|None=upnp\_device.get("\_host")

ifnotip\_address:

return

try:

bridge=awaitlinkplay\_factory\_httpapi\_bridge(ip\_address, session)

bridges\[bridge.device.uuid\] =bridge

exceptLinkPlayRequestException:

pass

awaitasync\_search(

search\_target=UPNP\_DEVICE\_TYPE, async\_callback=add\_linkplay\_device\_to\_list

)

\# Discover additional bridges through grouped multirooms

ifdiscovery\_through\_multiroom:

multiroom\_discovered\_bridges: dict\[str, LinkPlayBridge\] = {}

forbridgeinbridges.values():

fornew\_bridgeinawaitdiscover\_bridges\_through\_multiroom(bridge, session):

multiroom\_discovered\_bridges\[new\_bridge.device.uuid\] =new\_bridge

bridges=bridges\|multiroom\_discovered\_bridges

returnlist(bridges.values())

asyncdefdiscover\_bridges\_through\_multiroom(

bridge: LinkPlayBridge, session: ClientSession

) ->list\[LinkPlayBridge\]:

"""Discovers bridges through the multiroom of the provided bridge."""

try:

properties: dict\[Any, Any\] =awaitbridge.json\_request(

LinkPlayCommand.MULTIROOM\_LIST

)

ifint(properties\[MultiroomAttribute.NUM\_FOLLOWERS\]) ==0:

return \[\]

followers: list\[LinkPlayBridge\] = \[\]

forfollowerinproperties\[MultiroomAttribute.FOLLOWER\_LIST\]:

try:

new\_bridge=awaitlinkplay\_factory\_httpapi\_bridge(

follower\[MultiroomAttribute.IP\], session

)

followers.append(new\_bridge)

exceptLinkPlayRequestException:

pass

returnfollowers

exceptLinkPlayInvalidDataException:

return \[\]

You can’t perform that action at this time.