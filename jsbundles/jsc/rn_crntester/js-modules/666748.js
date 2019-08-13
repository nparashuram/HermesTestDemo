__d(function(g,r,i,a,m,e,d){'use strict';var t=r(d[0]).StyleSheet.create({content:{backgroundColor:'deepskyblue',borderWidth:1,borderColor:'dodgerblue',padding:20,margin:20,borderRadius:10,alignItems:'center'},rotatingImage:{width:70,height:70}});e.framework='React',e.title='Animated - Examples',e.description="Animated provides a powerful and easy-to-use API for building modern, interactive user experiences.",e.examples=[{title:'FadeInView',description:"Uses a simple timing animation to bring opacity from 0 to 1 when the component mounts.",render:function(){var n=(function(t){function n(t){var s;return r(d[2])(this,n),(s=r(d[3])(this,r(d[4])(n).call(this,t))).state={fadeAnim:new(r(d[0]).Animated.Value)(0)},s}return r(d[1])(n,t),r(d[5])(n,[{key:"componentDidMount",value:function(){r(d[0]).Animated.timing(this.state.fadeAnim,{toValue:1,duration:2e3}).start()}},{key:"render",value:function(){return r(d[6]).createElement(r(d[0]).Animated.View,{style:{opacity:this.state.fadeAnim}},this.props.children)}}]),n})(r(d[6]).Component),s=(function(s){function o(t){var n;return r(d[2])(this,o),(n=r(d[3])(this,r(d[4])(o).call(this,t))).state={show:!0},n}return r(d[1])(o,s),r(d[5])(o,[{key:"render",value:function(){var s=this;return r(d[6]).createElement(r(d[0]).View,null,r(d[6]).createElement(r(d[7]),{onPress:function(){s.setState(function(t){return{show:!t.show}})}},"Press to ",this.state.show?'Hide':'Show'),this.state.show&&r(d[6]).createElement(n,null,r(d[6]).createElement(r(d[0]).View,{style:t.content},r(d[6]).createElement(r(d[0]).Text,null,"FadeInView"))))}}]),o})(r(d[6]).Component);return r(d[6]).createElement(s,null)}},{title:'Transform Bounce',description:"One `Animated.Value` is driven by a spring with custom constants and mapped to an ordered set of transforms.  Each transform has an interpolation to convert the value into the right range and units.",render:function(){var n=this;return this.anim=this.anim||new(r(d[0]).Animated.Value)(0),r(d[6]).createElement(r(d[0]).View,null,r(d[6]).createElement(r(d[7]),{onPress:function(){r(d[0]).Animated.spring(n.anim,{toValue:0,velocity:3,tension:-10,friction:1}).start()}},"Press to Fling it!"),r(d[6]).createElement(r(d[0]).Animated.View,{style:[t.content,{transform:[{scale:this.anim.interpolate({inputRange:[0,1],outputRange:[1,4]})},{translateX:this.anim.interpolate({inputRange:[0,1],outputRange:[0,500]})},{rotate:this.anim.interpolate({inputRange:[0,1],outputRange:['0deg','360deg']})}]}]},r(d[6]).createElement(r(d[0]).Text,null,"Transforms!")))}},{title:'Composite Animations with Easing',description:"Sequence, parallel, delay, and stagger with different easing functions.",render:function(){var n=this;return this.anims=this.anims||[1,2,3].map(function(){return new(r(d[0]).Animated.Value)(0)}),r(d[6]).createElement(r(d[0]).View,null,r(d[6]).createElement(r(d[7]),{onPress:function(){var t=r(d[0]).Animated.timing;r(d[0]).Animated.sequence([t(n.anims[0],{toValue:200,easing:r(d[0]).Easing.linear}),r(d[0]).Animated.delay(400),t(n.anims[0],{toValue:0,easing:r(d[0]).Easing.elastic(2)}),r(d[0]).Animated.delay(400),r(d[0]).Animated.stagger(200,n.anims.map(function(n){return t(n,{toValue:200})}).concat(n.anims.map(function(n){return t(n,{toValue:0})}))),r(d[0]).Animated.delay(400),r(d[0]).Animated.parallel([r(d[0]).Easing.inOut(r(d[0]).Easing.quad),r(d[0]).Easing.back(1.5),r(d[0]).Easing.ease].map(function(s,o){return t(n.anims[o],{toValue:320,easing:s,duration:3e3})})),r(d[0]).Animated.delay(400),r(d[0]).Animated.stagger(200,n.anims.map(function(n){return t(n,{toValue:0,easing:r(d[0]).Easing.bounce,duration:2e3})}))]).start()}},"Press to Animate"),['Composite','Easing','Animations!'].map(function(s,o){return r(d[6]).createElement(r(d[0]).Animated.View,{key:s,style:[t.content,{left:n.anims[o]}]},r(d[6]).createElement(r(d[0]).Text,null,s))}))}},{title:'Rotating Images',description:'Simple Animated.Image rotation.',render:function(){var n=this;return this.anim=this.anim||new(r(d[0]).Animated.Value)(0),r(d[6]).createElement(r(d[0]).View,null,r(d[6]).createElement(r(d[7]),{onPress:function(){r(d[0]).Animated.spring(n.anim,{toValue:0,velocity:3,tension:-10,friction:1}).start()}},"Press to Spin it!"),r(d[6]).createElement(r(d[0]).Animated.Image,{source:r(d[8]),style:[t.rotatingImage,{transform:[{scale:this.anim.interpolate({inputRange:[0,1],outputRange:[1,10]})},{translateX:this.anim.interpolate({inputRange:[0,1],outputRange:[0,100]})},{rotate:this.anim.interpolate({inputRange:[0,1],outputRange:['0deg','360deg']})}]}]}))}},{title:'Continuous Interactions',description:"Gesture events, chaining, 2D values, interrupting and transitioning animations, etc.",render:function(){return r(d[6]).createElement(r(d[0]).Text,null,"Checkout the Gratuitous Animation App!")}}]},666748,[516,614,616,617,620,621,514,666749,666704]);